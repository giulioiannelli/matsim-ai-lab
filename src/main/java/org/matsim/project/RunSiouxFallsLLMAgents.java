package org.matsim.project;

import java.net.URL;
import java.util.concurrent.Callable;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.simwrapper.SimWrapperModule;

import matsimBinding.LLMConfigGroup;
import matsimBinding.LLMConfigGroup.BackendType;
import matsimBinding.LLMIntegrationModule;
import matsimBinding.LLMReplanningStrategyModule;
import matsimBinding.profile.ModelProfile;
import matsimBinding.profile.ModelProfileApplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Runs Sioux Falls with LLM-powered agent replanning using the upstream
 * {@link LLMIntegrationModule} (replanning strategy with tool-calling loop).
 *
 * <p>Prerequisites:
 * <ol>
 *   <li>Ollama running with a tool-calling model (e.g. {@code ollama pull qwen3.5})</li>
 *   <li>Qdrant running: {@code docker run -d -p 6333:6333 -p 6334:6334 qdrant/qdrant}</li>
 * </ol>
 *
 * <p>Usage (picocli-powered, positional + option flags):
 * <pre>
 *   ./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.RunSiouxFallsLLMAgents" \
 *     -Dexec.args="5 qwen3.5 --thinking-token-cap=2048"
 * </pre>
 *
 * <p>Positional args (index 0 = iterations, index 1 = model name) are preserved
 * for backward compatibility with the earlier positional-only CLI.
 */
@Command(name = "RunSiouxFallsLLMAgents",
         mixinStandardHelpOptions = true,
         description = "Sioux Falls + LLM replanning.")
public final class RunSiouxFallsLLMAgents implements Callable<Integer> {

    @Parameters(index = "0", description = "Last MATSim iteration (0-based).", defaultValue = "20")
    private int iterations;

    @Parameters(index = "1", arity = "0..1", description = "Model name key (looked up in model-profiles.yaml).",
                defaultValue = "qwen3:14b")
    private String modelName;

    @Option(names = {"--model-profile"},
            description = "Override the model-profile lookup key (defaults to model name).")
    private String modelProfileOverride;

    @Option(names = {"--model-profiles-path"},
            description = "Path to the model profiles YAML.",
            defaultValue = "config/model-profiles.yaml")
    private String modelProfilesPath;

    @Option(names = {"--thinking-token-cap"},
            description = "Override thinkingTokenCap (0 = unlimited).")
    private Integer thinkingTokenCapOverride;

    @Option(names = {"--num-agents"},
            description = "Number of AI agents per run.",
            defaultValue = "5")
    private int numAgents;

    @Option(names = {"--prompt-variant"},
            description = "Which system+task prompt to feed the LLM: 'legacy' (rule-centric, baseline) "
                    + "or 'persona' (first-person, persona-framed with attribute injection).",
            defaultValue = "legacy")
    private String promptVariant;

    @Option(names = {"--context-window"},
            description = "Override total context window in tokens (Ollama num_ctx). 0 = leave backend default.")
    private Integer contextWindowOverride;

    @Option(names = {"--enable-comparison-tools"},
            description = "Register compare_routes and evaluate_plan tools and advertise them in the system prompt.")
    private boolean enableComparisonTools;

    @Option(names = {"--max-tool-iterations"},
            description = "Max tool-calling rounds before the agent is forced to stop (default 10).",
            defaultValue = "10")
    private int maxToolIterations;

    @Option(names = {"--seed"},
            description = "MATSim global random seed. Controls which agents get picked for LLM replanning "
                    + "and mobsim stochasticity. Use distinct seeds for independent replicate runs.")
    private Long randomSeed;

    public static void main(String[] args) {
        int exit = new CommandLine(new RunSiouxFallsLLMAgents()).execute(args);
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
        if (randomSeed != null) {
            config.global().setRandomSeed(randomSeed);
        }

        LLMConfigGroup llmConfig = new LLMConfigGroup();

        // Local Ollama is the default target; the backend starts at OPENAI_COMPAT
        // (the broadly-compatible shim path) and the profile applier can promote
        // it to OLLAMA_NATIVE when a profile asks for it.
        llmConfig.setBackend(BackendType.OPENAI_COMPAT);
        llmConfig.setLlmHost("localhost");
        llmConfig.setLlmPort(11434);
        // llmPath left null → the backend's default path is used.
        llmConfig.setModelName(modelProfileOverride != null ? modelProfileOverride : modelName);
        llmConfig.setUseHttps(false);
        llmConfig.setAuthorization("ollama");
        llmConfig.setTemperature(0.3);
        llmConfig.setMaxTokens(4096);
        llmConfig.setModelProfilesPath(modelProfilesPath);

        // Resolve and apply per-model overrides from config/model-profiles.yaml.
        ModelProfile profile = ModelProfileApplier.apply(llmConfig);

        // Run-scoped CLI overrides beat profile values.
        if (thinkingTokenCapOverride != null) {
            llmConfig.setThinkingTokenCap(thinkingTokenCapOverride);
        }
        if (contextWindowOverride != null) {
            llmConfig.setContextWindowTokens(contextWindowOverride);
        }
        llmConfig.setPromptVariant(promptVariant);
        llmConfig.setComparisonToolsEnabled(enableComparisonTools);

        // Model name must reflect what we actually send to the server; restore if
        // the profile key differed from the true Ollama tag.
        llmConfig.setModelName(modelName);

        // Ollama embedding endpoint for RAG.
        llmConfig.setEmbeddingPath("/v1/embeddings");
        llmConfig.setEmbeddingModelName("nomic-embed-text");

        llmConfig.setVectorDbHost("localhost");
        llmConfig.setVectorDbPort(6334);
        llmConfig.setVectorDbCollectionName("matsim_siouxfalls_llm");
        llmConfig.setCleanVectorDbUponCompletion("ALL");

        llmConfig.setNumberOfAIAgents(numAgents);
        llmConfig.setIterationToStartAIActivity(0);
        llmConfig.setMaxToolIterations(maxToolIterations);

        String outputDir = "./output/" + composeOutputDirName("siouxfalls", llmConfig, randomSeed);
        config.controller().setOutputDirectory(outputDir);

        config.addModule(llmConfig);

        // AI agents live in their own subpopulation whose only strategy is the
        // LLM planner, so every selected agent is queried each iteration instead
        // of competing in the whole-population strategy lottery. The default
        // strategies (loaded from the scenario config) stay on the default
        // subpopulation for everyone else. The new subpopulation needs its own
        // scoring parameters, so mirror the default ones onto it.
        mirrorDefaultScoringToSubpopulation(config, LLMReplanningStrategyModule.LLM_SUBPOPULATION);
        // The scenario's strategies are registered under the null default
        // subpopulation. Non-AI agents now sit in the explicit "default"
        // subpopulation, so re-key those strategies to match; otherwise they
        // would have no strategy and never replan.
        for (org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings ss
                : config.replanning().getStrategySettings()) {
            if (ss.getSubpopulation() == null) {
                ss.setSubpopulation(org.matsim.core.config.groups.ScoringConfigGroup.DEFAULT_SUBPOPULATION);
            }
        }
        config.replanning().addStrategySettings(
            new org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings()
                .setStrategyName(LLMReplanningStrategyModule.StrategyName)
                .setWeight(1.0)
                .setSubpopulation(LLMReplanningStrategyModule.LLM_SUBPOPULATION)
        );

        Scenario scenario = ScenarioUtils.loadScenario(config);
        // Tag AI agents and assign subpopulations BEFORE building the controler, so
        // subpopulation-aware core listeners (ScoreStats, scoring) see the final
        // subpopulations when they initialise at startup.
        LLMReplanningStrategyModule.assignAISubpopulations(
                scenario, numAgents, config.global().getRandomSeed());
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new SimWrapperModule());
        controler.addOverridingModule(new LLMIntegrationModule(
                LLMIntegrationModule.ConnectionType.replanning,
                llmConfig.isComparisonToolsEnabled()));

        System.out.println("\n=== Running Sioux Falls with LLM-Powered Agent Replanning ===");
        System.out.println("Iterations: " + iterations);
        System.out.println("Model: " + modelName);
        System.out.println("Profile: " + profile.name()
                + "  reasoning=" + llmConfig.isReasoningModel()
                + "  thinking=" + llmConfig.isEnableThinking()
                + "  thinkingCap=" + llmConfig.getThinkingTokenCap());
        System.out.println("AI agents: " + numAgents + " (from iteration 0)");
        System.out.println("Prompt variant: " + llmConfig.getPromptVariant());
        System.out.println("Context window: " + llmConfig.getContextWindowTokens() + " tokens");
        System.out.println("============================================================\n");

        controler.run();

        System.out.println("\n=== Simulation Complete ===");
        System.out.println("Output: " + outputDir + "/");
        System.out.println("===========================\n");
        return 0;
    }

    /**
     * Builds a self-identifying output directory name encoding the model and key
     * LLM parameters plus any active feature flags.
     * Format: {@code <scenario>-<model>-T<temp>-N<maxtok>[-<flags>]} (filesystem-safe).
     */
    private static String composeOutputDirName(String scenario, LLMConfigGroup cfg, Long seed) {
        StringBuilder sb = new StringBuilder(scenario);
        sb.append('-').append(cfg.getModelName().replace(':', '-').replace('/', '-'));
        sb.append("-T").append(cfg.getTemperature());
        sb.append("-N").append(cfg.getMaxTokens());
        if ("staged".equalsIgnoreCase(System.getenv("MATSIM_LLM_TOOL_FILTER"))) sb.append("-staged");
        if (Boolean.getBoolean("matsim.llm.grammar")) sb.append("-grammar");
        if (cfg.isReasoningModel()) sb.append("-reasoning");
        if ("persona".equalsIgnoreCase(cfg.getPromptVariant())) sb.append("-persona");
        if (cfg.isComparisonToolsEnabled()) sb.append("-cmp");
        if (seed != null) sb.append("-s").append(seed);
        return sb.toString();
    }

    /**
     * Copies the default subpopulation's scoring parameters onto {@code subpop}
     * so agents placed there (the AI agents) are scored identically to everyone
     * else. MATSim resolves scoring parameters by subpopulation; without a set
     * for {@code subpop} those agents would have no scoring function. The copy is
     * generic (it mirrors whatever scalar/activity/mode params the scenario
     * defines) so it stays correct if the scenario's scoring changes.
     */
    static void mirrorDefaultScoringToSubpopulation(Config config, String subpop) {
        org.matsim.core.config.groups.ScoringConfigGroup scoring = config.scoring();
        // The scenario's default scoring lives under the null subpopulation, which
        // non-AI agents (no subpopulation attribute -> getSubpopulation()==null)
        // resolve to. The sole existing set is that default.
        org.matsim.core.config.groups.ScoringConfigGroup.ScoringParameterSet src =
                scoring.getScoringParametersPerSubpopulation().values().iterator().next();

        // getScoringParameters falls back to the null-keyed default for ANY unknown
        // subpopulation, so getOrCreateScoringParameters(x) would find the default
        // via that fallback and *remove* it. Detach the default while creating the
        // new sets, then restore it. We create two copies: one under the explicit
        // DEFAULT_SUBPOPULATION (MATSim requires it once several subpopulations
        // exist) and one under our AI subpopulation. The null default stays for
        // the non-AI agents.
        scoring.removeParameterSet(src);
        org.matsim.core.config.groups.ScoringConfigGroup.ScoringParameterSet def =
                scoring.getOrCreateScoringParameters(
                        org.matsim.core.config.groups.ScoringConfigGroup.DEFAULT_SUBPOPULATION);
        org.matsim.core.config.groups.ScoringConfigGroup.ScoringParameterSet dst =
                scoring.getOrCreateScoringParameters(subpop);
        scoring.addParameterSet(src);

        copyScoringParams(src, def);
        copyScoringParams(src, dst);
    }

    /** Deep-copy scalar, per-activity and per-mode params from one scoring set to another. */
    private static void copyScoringParams(
            org.matsim.core.config.groups.ScoringConfigGroup.ScoringParameterSet src,
            org.matsim.core.config.groups.ScoringConfigGroup.ScoringParameterSet dst) {
        src.getParams().forEach((k, v) -> {
            if (!"subpopulation".equals(k)) dst.addParam(k, v);
        });
        for (org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams ap : src.getActivityParams()) {
            org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams cp =
                    dst.getOrCreateActivityParams(ap.getActivityType());
            ap.getParams().forEach(cp::addParam);
        }
        for (org.matsim.core.config.groups.ScoringConfigGroup.ModeParams mp : src.getModes().values()) {
            org.matsim.core.config.groups.ScoringConfigGroup.ModeParams cp =
                    dst.getOrCreateModeParams(mp.getMode());
            mp.getParams().forEach(cp::addParam);
        }
    }
}
