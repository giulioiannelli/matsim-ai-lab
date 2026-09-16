package matsimBinding.panel;

import matsimBinding.panel.PanelSelection.Candidate;
import matsimBinding.panel.PanelSelection.Choice;
import matsimBinding.panel.PanelSelection.Reason;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelSelectionTest {

    private static Id<Person> id(String s) { return Id.createPersonId(s); }

    @Test
    void stuckBeatsUnreviewedBeatsScoreDrop() {
        List<Candidate> panel = List.of(
                new Candidate(id("drop"), false, true, 5.0),
                new Candidate(id("new"), false, false, null),
                new Candidate(id("stuck"), true, true, 0.0));
        List<Choice> choices = PanelSelection.choose(panel, 10, 1.0);
        assertEquals(List.of("stuck", "new", "drop"), choices.stream().map(c -> c.id().toString()).toList());
        assertEquals(Reason.STUCK, choices.get(0).reason());
        assertEquals(Reason.NEVER_REVIEWED, choices.get(1).reason());
        assertEquals(Reason.SCORE_DROP, choices.get(2).reason());
    }

    @Test
    void budgetCapsAndWorstDropComesFirst() {
        List<Candidate> panel = List.of(
                new Candidate(id("a"), false, true, 1.0),
                new Candidate(id("b"), false, true, 9.0),
                new Candidate(id("c"), false, true, 4.0));
        List<Choice> choices = PanelSelection.choose(panel, 2, 1.0);
        assertEquals(List.of("b", "c"), choices.stream().map(c -> c.id().toString()).toList());
    }

    @Test
    void quantileLimitsEligibleDrops() {
        List<Candidate> panel = List.of(
                new Candidate(id("a"), false, true, 1.0),
                new Candidate(id("b"), false, true, 9.0),
                new Candidate(id("c"), false, true, 4.0),
                new Candidate(id("d"), false, true, 2.0),
                new Candidate(id("e"), false, true, 3.0));
        List<Choice> choices = PanelSelection.choose(panel, 10, 0.2);
        assertEquals(1, choices.size());
        assertEquals("b", choices.get(0).id().toString());
    }

    @Test
    void improvedAndReviewedAgentsAreNotQueried() {
        List<Candidate> panel = List.of(
                new Candidate(id("better"), false, true, -2.0),
                new Candidate(id("same"), false, true, 0.0));
        assertTrue(PanelSelection.choose(panel, 10, 1.0).isEmpty());
    }
}
