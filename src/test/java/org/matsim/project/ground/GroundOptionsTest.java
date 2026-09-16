package org.matsim.project.ground;

import org.junit.jupiter.api.Test;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundOptionsTest {

    private static GroundOptions parse(String... args) {
        GroundOptions options = new GroundOptions();
        new CommandLine(options).parseArgs(args);
        return options;
    }

    @Test
    void sampleScalesCapacities() {
        Config config = ConfigUtils.createConfig();
        parse("--sample=0.1").applyToConfig(config);
        assertEquals(0.1, config.qsim().getFlowCapFactor(), 1e-12);
        assertEquals(Math.pow(0.1, GroundOptions.STORAGE_EXPONENT), config.qsim().getStorageCapFactor(), 1e-12);
    }

    @Test
    void capacityFactorWinsOverSampleWhenPlansArePreSampled() {
        GroundOptions options = parse("--capacity-factor=0.25", "--plans-file=warm.xml.gz");
        Config config = ConfigUtils.createConfig();
        options.applyToConfig(config);
        assertEquals(0.25, options.effectiveCapacityFactor(), 1e-12);
        assertEquals(0.25, config.qsim().getFlowCapFactor(), 1e-12);
        assertTrue(config.plans().getInputFile().endsWith("warm.xml.gz"));
        assertEquals("-c0.25-warm", options.tag());
    }

    @Test
    void defaultsLeaveConfigAlone() {
        Config config = ConfigUtils.createConfig();
        double flow = config.qsim().getFlowCapFactor();
        GroundOptions options = parse();
        options.applyToConfig(config);
        assertEquals(flow, config.qsim().getFlowCapFactor(), 1e-12);
        assertEquals("", options.tag());
    }

    @Test
    void rejectsSampleOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> parse("--sample=1.5").applyToConfig(ConfigUtils.createConfig()));
    }
}
