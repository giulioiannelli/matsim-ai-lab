package matsimBinding.panel;

import jakarta.inject.Inject;
import matsimBinding.LLMConfigGroup;
import matsimBinding.LLMReplanningStrategyModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.replanning.GenericPlanStrategy;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.choosers.StrategyChooser;
import org.matsim.core.replanning.choosers.WeightedStrategyChooser;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strategy chooser that forces the LLM strategy on a budgeted subset of the
 * panel (agents tagged {@code isAI}) each iteration and delegates everyone else
 * to MATSim's weighted lottery. The LLM strategy is registered with weight 0,
 * so the lottery itself never picks it.
 *
 * <p>Selection logic lives in {@link PanelSelection}; this class supplies the
 * per-agent experience (executed-score drop since the last replanning, stuck in
 * the last mobsim run, never reviewed) and writes
 * {@code llm_panel_selection.csv} to the output directory.
 */
public final class PanelStrategyChooser implements StrategyChooser<Plan, Person>, ShutdownListener {

    private static final Logger log = LogManager.getLogger(PanelStrategyChooser.class);

    private final LLMConfigGroup llmConfig;
    private final Config config;
    private final Scenario scenario;
    private final PanelExperienceTracker tracker;
    private final OutputDirectoryHierarchy outputHierarchy;
    private final PlanStrategy llmStrategy;
    private final WeightedStrategyChooser<Plan, Person> lottery = new WeightedStrategyChooser<>();

    private final Map<Id<Person>, Double> lastScore = new HashMap<>();
    private final Set<Id<Person>> reviewed = new HashSet<>();
    private final Set<Id<Person>> chosen = new HashSet<>();
    private BufferedWriter csv;

    @Inject
    PanelStrategyChooser(LLMConfigGroup llmConfig, Config config, Scenario scenario,
                         PanelExperienceTracker tracker, OutputDirectoryHierarchy outputHierarchy,
                         Map<StrategySettings, PlanStrategy> strategies) {
        this.llmConfig = llmConfig;
        this.config = config;
        this.scenario = scenario;
        this.tracker = tracker;
        this.outputHierarchy = outputHierarchy;
        this.llmStrategy = strategies.entrySet().stream()
                .filter(e -> LLMReplanningStrategyModule.StrategyName.equals(e.getKey().getStrategyName()))
                .map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Panel mode needs the " + LLMReplanningStrategyModule.StrategyName + " strategy registered."));
    }

    @Override
    public void beforeReplanning(ReplanningContext context) {
        chosen.clear();
        int iteration = context.getIteration();
        List<PanelSelection.Candidate> panel = new ArrayList<>();
        for (Person person : scenario.getPopulation().getPersons().values()) {
            if (!Boolean.TRUE.equals(person.getAttributes().getAttribute("isAI"))) continue;
            Double score = person.getSelectedPlan().getScore();
            Double previous = lastScore.get(person.getId());
            Double drop = (score == null || previous == null) ? null : previous - score;
            panel.add(new PanelSelection.Candidate(person.getId(), tracker.wasStuck(person.getId()),
                    reviewed.contains(person.getId()), drop));
            if (score != null) lastScore.put(person.getId(), score);
        }
        if (!llmActive(iteration)) {
            log.info("Panel: LLM inactive in iteration {} (start {}, innovation off after {}).",
                    iteration, llmConfig.getIterationToStartAIActivity(), innovationOffIteration());
            return;
        }
        List<PanelSelection.Choice> choices = PanelSelection.choose(
                panel, llmConfig.getMaxQueriesPerIteration(), llmConfig.getTriggerScoreDropQuantile());
        for (PanelSelection.Choice choice : choices) {
            chosen.add(choice.id());
            reviewed.add(choice.id());
            writeRow(iteration, choice);
        }
        log.info("Panel: {} of {} agents chosen for LLM replanning in iteration {} (budget {}).",
                chosen.size(), panel.size(), iteration, llmConfig.getMaxQueriesPerIteration());
    }

    @Override
    public GenericPlanStrategy<Plan, Person> chooseStrategy(HasPlansAndId<Plan, Person> person, String subpopulation,
                                                            ReplanningContext context, Weights<Plan, Person> weights) {
        if (chosen.contains(person.getId())) {
            for (int i = 0; i < weights.size(); i++) {
                if (weights.getStrategy(i) == llmStrategy) return llmStrategy;
            }
            log.warn("Panel: LLM strategy not registered for subpopulation '{}'; falling back to the lottery.", subpopulation);
        }
        return lottery.chooseStrategy(person, subpopulation, context, weights);
    }

    private boolean llmActive(int iteration) {
        return iteration >= llmConfig.getIterationToStartAIActivity() && iteration <= innovationOffIteration();
    }

    /** Last iteration with innovation, computed exactly as MATSim's StrategyManager does. */
    private int innovationOffIteration() {
        int first = config.controller().getFirstIteration();
        int last = config.controller().getLastIteration();
        return (int) ((last - first) * config.replanning().getFractionOfIterationsToDisableInnovation() + first);
    }

    private void writeRow(int iteration, PanelSelection.Choice choice) {
        try {
            if (csv == null) {
                csv = new BufferedWriter(new FileWriter(outputHierarchy.getOutputFilename("llm_panel_selection.csv")));
                csv.write("iteration,personId,reason,scoreDrop\n");
            }
            csv.write(iteration + "," + choice.id() + "," + choice.reason() + "," + choice.scoreDrop() + "\n");
            csv.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write panel selection row", e);
        }
    }

    @Override
    public void notifyShutdown(ShutdownEvent event) {
        try {
            if (csv != null) csv.close();
        } catch (IOException e) {
            log.warn("Could not close panel selection CSV", e);
        }
    }
}
