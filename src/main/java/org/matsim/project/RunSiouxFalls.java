package org.matsim.project;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.project.ground.GroundOptions;
import org.matsim.project.ground.InnovationBoost;
import org.matsim.simwrapper.SimWrapperModule;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URL;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Runs the Sioux Falls 2014 scenario from matsim-examples without any LLM
 * involvement: the rule-based baseline and the warm-up producer.
 *
 * <p>Multimodal scenario with car, public transit (bus), and walk; dynamic and
 * disaggregate demand with activity-based plans; full-scale by default
 * (flowCapacityFactor=1.0) with the real PT schedule.
 *
 * <p>Warm-up recipe (plans of the last iteration land in
 * {@code output/<run>/output_plans.xml.gz} and can be fed to any runner with
 * {@code --plans-file}):
 * <pre>
 *   ./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.RunSiouxFalls" \
 *     -Dexec.args="100 --sample=0.1 --innovation-boost=10 --boost-until=50 --seed=4711"
 * </pre>
 */
@Command(name = "RunSiouxFalls",
         mixinStandardHelpOptions = true,
         description = "Sioux Falls rule-based baseline / warm-up.")
public final class RunSiouxFalls implements Callable<Integer> {

    @Parameters(index = "0", description = "Last MATSim iteration (0-based).", defaultValue = "100")
    private int iterations;

    @Option(names = {"--seed"}, description = "MATSim global random seed (population sampling and mobsim).")
    private Long randomSeed;

    @Option(names = {"--innovation-boost"},
            description = "Multiply the weight of innovative strategies (ReRoute, SubtourModeChoice, TimeAllocationMutator) by this factor. 1 = off.",
            defaultValue = "1.0")
    private double innovationBoost;

    @Option(names = {"--boost-until"},
            description = "Iteration at which boosted weights return to their configured values. Default: half of the iterations.")
    private Integer boostUntil;

    @Option(names = {"--disable-innovation-after"},
            description = "Fraction of iterations after which innovative strategies are switched off (MATSim core setting).",
            defaultValue = "0.8")
    private double disableInnovationAfter;

    @Option(names = {"--output"},
            description = "Output directory. Default: output/siouxfalls[-sNN][-warm][-bX] derived from the options.")
    private String outputDir;

    @Mixin
    private GroundOptions ground = new GroundOptions();

    public static void main(String[] args) {
        int exit = new CommandLine(new RunSiouxFalls()).execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() {
        URL context = org.matsim.examples.ExamplesUtils.getTestScenarioURL("siouxfalls-2014");
        URL url = IOUtils.extendUrl(context, "config_default.xml");

        Config config = ConfigUtils.loadConfig(url);
        config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(iterations);
        config.controller().setWriteEventsInterval(iterations);
        config.controller().setWritePlansInterval(iterations);
        config.replanning().setFractionOfIterationsToDisableInnovation(disableInnovationAfter);
        if (randomSeed != null) {
            config.global().setRandomSeed(randomSeed);
        }
        ground.applyToConfig(config);

        int until = boostUntil != null ? boostUntil : iterations / 2;
        InnovationBoost.applyToConfig(config, innovationBoost);

        String runName = composeOutputDirName();
        config.controller().setOutputDirectory(outputDir != null ? outputDir : "./output/" + runName);

        Scenario scenario = ScenarioUtils.loadScenario(config);
        ground.applyToScenario(scenario);

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new SimWrapperModule());
        controler.addOverridingModule(new InnovationBoost.Module(innovationBoost, until));
        controler.addOverridingModule(new org.matsim.project.progress.EtaReporter.Module());

        System.out.println("\n=== Running Sioux Falls (rule-based) ===");
        System.out.println("Iterations: " + iterations + "  seed: " + config.global().getRandomSeed());
        System.out.println("Agents: " + scenario.getPopulation().getPersons().size()
                + "  capacity factor: " + ground.effectiveCapacityFactor());
        System.out.println("Innovation boost: x" + innovationBoost + " until it." + until
                + "; innovation off after " + disableInnovationAfter + " of the run");
        System.out.println("Output: " + config.controller().getOutputDirectory());
        System.out.println("========================================\n");

        controler.run();
        return 0;
    }

    private String composeOutputDirName() {
        StringBuilder sb = new StringBuilder("siouxfalls").append(ground.tag());
        if (innovationBoost != 1.0) sb.append("-b").append(String.format(Locale.ROOT, "%.0f", innovationBoost));
        if (randomSeed != null) sb.append("-seed").append(randomSeed);
        return sb.toString();
    }
}
