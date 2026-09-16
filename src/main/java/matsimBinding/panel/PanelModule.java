package matsimBinding.panel;

import com.google.inject.TypeLiteral;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.replanning.choosers.StrategyChooser;

/**
 * Installs panel replanning: replaces MATSim's weighted strategy chooser with
 * {@link PanelStrategyChooser} and tracks stuck agents for its trigger.
 * Requires {@code LLMIntegrationModule} in replanning mode and the LLM strategy
 * registered (weight 0) for the panel agents' subpopulation.
 */
public final class PanelModule extends AbstractModule {

    @Override
    public void install() {
        bind(PanelExperienceTracker.class).asEagerSingleton();
        addEventHandlerBinding().to(PanelExperienceTracker.class);
        bind(PanelStrategyChooser.class).asEagerSingleton();
        bind(new TypeLiteral<StrategyChooser<Plan, Person>>() {}).to(PanelStrategyChooser.class);
        addControlerListenerBinding().to(PanelStrategyChooser.class);
    }
}
