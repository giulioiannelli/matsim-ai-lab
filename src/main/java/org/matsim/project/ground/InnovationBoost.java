package org.matsim.project.ground;

import org.matsim.core.config.Config;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.replanning.GenericPlanStrategy;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.ReplanningUtils;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule.DefaultSelector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Temporarily multiplies the weight of every innovative strategy (anything that
 * is not a pure plan selector) so a scenario reaches a relaxed state in fewer
 * iterations, then restores the original weights at a chosen iteration.
 *
 * <p>Usage: {@link #applyToConfig(Config, double)} before the controler is
 * built, then {@code controler.addOverridingModule(new InnovationBoost.Module(factor, until))}
 * so the weights are divided back at iteration {@code until}. The restore is
 * scheduled through the same change-request mechanism MATSim uses to switch
 * innovation off, so both coexist: if {@code until} lies beyond the innovation
 * switch-off, the innovative weights are already zero and stay zero.
 */
public final class InnovationBoost {

    private static final Logger log = LogManager.getLogger(InnovationBoost.class);

    private static final Set<String> SELECTORS = Set.of(
            DefaultSelector.KeepLastSelected, DefaultSelector.BestScore, DefaultSelector.ChangeExpBeta,
            DefaultSelector.SelectExpBeta, DefaultSelector.SelectRandom, DefaultSelector.SelectPathSizeLogit);

    private InnovationBoost() {}

    /** Multiplies the configured weight of every non-selector strategy by {@code factor}. */
    public static void applyToConfig(Config config, double factor) {
        if (factor <= 0) throw new IllegalArgumentException("boost factor must be positive, got " + factor);
        if (factor == 1.0) return;
        for (StrategySettings ss : config.replanning().getStrategySettings()) {
            if (isInnovative(ss.getStrategyName())) {
                ss.setWeight(ss.getWeight() * factor);
            }
        }
    }

    public static boolean isInnovative(String strategyName) {
        return !SELECTORS.contains(strategyName);
    }

    /** Schedules the restore of the original weights at iteration {@code until}. */
    public static final class Module extends AbstractModule {
        private final double factor;
        private final int until;

        public Module(double factor, int until) {
            this.factor = factor;
            this.until = until;
        }

        @Override
        public void install() {
            if (factor == 1.0) return;
            bind(Settings.class).toInstance(new Settings(factor, until));
            addControlerListenerBinding().to(RestoreListener.class);
        }
    }

    record Settings(double factor, int until) {}

    static final class RestoreListener implements StartupListener {
        private final StrategyManager strategyManager;
        private final Config config;
        private final Settings settings;

        @Inject
        RestoreListener(StrategyManager strategyManager, Config config, Settings settings) {
            this.strategyManager = strategyManager;
            this.config = config;
            this.settings = settings;
        }

        @Override
        public void notifyStartup(StartupEvent event) {
            Set<String> subpopulations = new LinkedHashSet<>();
            for (StrategySettings ss : config.replanning().getStrategySettings()) {
                subpopulations.add(ss.getSubpopulation());
            }
            int scheduled = 0;
            for (String subpop : subpopulations) {
                List<GenericPlanStrategy<org.matsim.api.core.v01.population.Plan, org.matsim.api.core.v01.population.Person>> strategies =
                        strategyManager.getStrategies(subpop);
                List<Double> weights = strategyManager.getWeights(subpop);
                for (int i = 0; i < strategies.size(); i++) {
                    GenericPlanStrategy<?, ?> strategy = strategies.get(i);
                    if (ReplanningUtils.isOnlySelector(strategy) || !(strategy instanceof PlanStrategy ps)) continue;
                    double restored = weights.get(i) / settings.factor();
                    strategyManager.addChangeRequest(settings.until(), ps, subpop, restored);
                    scheduled++;
                }
            }
            log.info("Innovation boost x{} active until iteration {}: {} strategy weight(s) will be restored.",
                    settings.factor(), settings.until(), scheduled);
        }
    }
}
