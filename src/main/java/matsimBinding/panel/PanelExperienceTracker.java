package matsimBinding.panel;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.population.Person;

import java.util.HashSet;
import java.util.Set;

/** Remembers which agents got stuck in the last mobsim run. */
public final class PanelExperienceTracker implements PersonStuckEventHandler {

    private final Set<Id<Person>> stuck = new HashSet<>();

    @Override
    public void handleEvent(PersonStuckEvent event) {
        stuck.add(event.getPersonId());
    }

    @Override
    public void reset(int iteration) {
        stuck.clear();
    }

    public boolean wasStuck(Id<Person> personId) {
        return stuck.contains(personId);
    }
}
