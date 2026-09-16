package org.matsim.project.ground;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.Locale;

/**
 * Reusable CLI options that decide which "ground" a run starts from: an
 * alternative plans file (typically the warmed plans of an earlier run) and a
 * population sample with matching network capacities.
 *
 * <p>Attach to a picocli runner with {@code @Mixin GroundOptions ground}, then
 * call {@link #applyToConfig(Config)} before the scenario is loaded and
 * {@link #applyToScenario(Scenario)} right after.
 *
 * <p>Two knobs cover the two situations:
 * <ul>
 *   <li>{@code --sample f}: draw a random {@code f} share of the loaded population
 *       and scale flow/storage capacities to match. Use when starting from the
 *       full-size scenario plans.</li>
 *   <li>{@code --capacity-factor f}: scale capacities only. Use when the plans
 *       file already contains a sampled population (for example the warmed plans
 *       of a {@code --sample} run).</li>
 * </ul>
 */
public final class GroundOptions {

    /** Storage capacity is conventionally scaled with a softer exponent than flow. */
    public static final double STORAGE_EXPONENT = 0.75;

    @Option(names = {"--plans-file"},
            description = "Plans file replacing the scenario's population (e.g. the output_plans.xml.gz of a warm-up run).")
    private String plansFile;

    @Option(names = {"--sample"},
            description = "Keep this share of the population (0 < f <= 1) and scale capacities accordingly. 1 = full population.",
            defaultValue = "1.0")
    private double sample;

    @Option(names = {"--capacity-factor"},
            description = "Scale flow/storage capacity without resampling (for plans that are already a sample). 0 = derive from --sample.",
            defaultValue = "0")
    private double capacityFactor;

    public void applyToConfig(Config config) {
        if (sample <= 0 || sample > 1) {
            throw new IllegalArgumentException("--sample must be in (0, 1], got " + sample);
        }
        if (plansFile != null) {
            // Absolute path: the config context may be a jar URL, which would
            // otherwise swallow a relative path.
            config.plans().setInputFile(new File(plansFile).getAbsolutePath());
        }
        double flow = effectiveCapacityFactor();
        if (flow < 1.0) {
            config.qsim().setFlowCapFactor(flow);
            config.qsim().setStorageCapFactor(Math.pow(flow, STORAGE_EXPONENT));
        }
    }

    public void applyToScenario(Scenario scenario) {
        if (sample < 1.0) {
            MatsimRandom.reset(scenario.getConfig().global().getRandomSeed());
            PopulationUtils.sampleDown(scenario.getPopulation(), sample);
        }
    }

    /** The share of the real population the mobsim should assume. */
    public double effectiveCapacityFactor() {
        return capacityFactor > 0 ? capacityFactor : sample;
    }

    public boolean hasPlansFile() {
        return plansFile != null;
    }

    public String getPlansFile() {
        return plansFile;
    }

    public double getSample() {
        return sample;
    }

    /** Short, filesystem-safe tag for output directory names ("" when nothing was changed). */
    public String tag() {
        StringBuilder sb = new StringBuilder();
        if (sample < 1.0) sb.append("-s").append(String.format(Locale.ROOT, "%.2f", sample));
        else if (capacityFactor > 0 && capacityFactor < 1.0) sb.append("-c").append(String.format(Locale.ROOT, "%.2f", capacityFactor));
        if (plansFile != null) sb.append("-warm");
        return sb.toString();
    }
}
