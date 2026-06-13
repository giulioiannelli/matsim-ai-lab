package org.matsim.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URL;

import org.junit.jupiter.api.Test;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;

import matsimBinding.LLMReplanningStrategyModule;

/**
 * The AI subpopulation must get its own scoring parameters and the resulting
 * config must pass MATSim's consistency check. Runs in milliseconds — no mobsim,
 * no LLM — so the subpopulation/scoring wiring is verified offline before any
 * simulation.
 */
class BlockerASubpopConfigTest {

    @Test
    void llmSubpopulationGetsScoringAndConfigIsConsistent() {
        URL context = ExamplesUtils.getTestScenarioURL("siouxfalls-2014");
        Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(context, "config_default.xml"));

        System.out.println("before scoring subpop keys = "
                + config.scoring().getScoringParametersPerSubpopulation().keySet());

        RunSiouxFallsLLMAgents.mirrorDefaultScoringToSubpopulation(
                config, LLMReplanningStrategyModule.LLM_SUBPOPULATION);

        System.out.println("after  scoring subpop keys = "
                + config.scoring().getScoringParametersPerSubpopulation().keySet());

        for (ReplanningConfigGroup.StrategySettings ss : config.replanning().getStrategySettings()) {
            if (ss.getSubpopulation() == null) {
                ss.setSubpopulation(org.matsim.core.config.groups.ScoringConfigGroup.DEFAULT_SUBPOPULATION);
            }
        }
        config.replanning().addStrategySettings(
                new ReplanningConfigGroup.StrategySettings()
                        .setStrategyName(LLMReplanningStrategyModule.StrategyName)
                        .setWeight(1.0)
                        .setSubpopulation(LLMReplanningStrategyModule.LLM_SUBPOPULATION));

        // The default subpopulation must still resolve (this is what blew up).
        assertNotNull(config.scoring().getActivityParams("home"),
                "default subpopulation scoring must survive adding the llm subpopulation");

        var llm = config.scoring().getScoringParameters(LLMReplanningStrategyModule.LLM_SUBPOPULATION);
        assertNotNull(llm, "llm subpopulation must have scoring params");
        assertFalse(llm.getActivityParams().isEmpty(), "llm scoring must have activity params");

        config.checkConsistency();
    }
}
