package org.matsim.project.ground;

import org.junit.jupiter.api.Test;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InnovationBoostTest {

    private static Config configWith(String name, double weight) {
        Config config = ConfigUtils.createConfig();
        config.replanning().addStrategySettings(new StrategySettings().setStrategyName(name).setWeight(weight));
        return config;
    }

    private static double weightOf(Config config, String name) {
        return config.replanning().getStrategySettings().stream()
                .filter(ss -> ss.getStrategyName().equals(name))
                .findFirst().orElseThrow().getWeight();
    }

    @Test
    void boostsInnovativeStrategiesOnly() {
        Config config = configWith("ChangeExpBeta", 0.7);
        config.replanning().addStrategySettings(new StrategySettings().setStrategyName("ReRoute").setWeight(0.01));
        InnovationBoost.applyToConfig(config, 10.0);
        assertEquals(0.7, weightOf(config, "ChangeExpBeta"), 1e-12);
        assertEquals(0.1, weightOf(config, "ReRoute"), 1e-12);
    }

    @Test
    void factorOneLeavesWeightsUntouched() {
        Config config = configWith("SubtourModeChoice", 0.01);
        InnovationBoost.applyToConfig(config, 1.0);
        assertEquals(0.01, weightOf(config, "SubtourModeChoice"), 1e-12);
    }

    @Test
    void rejectsNonPositiveFactor() {
        assertThrows(IllegalArgumentException.class, () -> InnovationBoost.applyToConfig(configWith("ReRoute", 0.01), 0));
    }

    @Test
    void classifiesSelectors() {
        assertFalse(InnovationBoost.isInnovative("ChangeExpBeta"));
        assertFalse(InnovationBoost.isInnovative("BestScore"));
        assertTrue(InnovationBoost.isInnovative("TimeAllocationMutator"));
        assertTrue(InnovationBoost.isInnovative("LLMReplanningStrategy"));
    }
}
