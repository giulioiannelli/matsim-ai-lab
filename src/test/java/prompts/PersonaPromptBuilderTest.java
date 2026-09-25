package prompts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.population.PopulationUtils;

/**
 * Persona lines must not contradict what {@code available_modes} reports. A
 * person who owns a car but has no driving licence cannot "use a car whenever
 * they want"; the prompt must reconcile car ownership with the licence rather
 * than emit the two as independent, conflicting bullet points.
 */
class PersonaPromptBuilderTest {

    private static Person personWith(String... kv) {
        Person p = PopulationUtils.getFactory().createPerson(Id.create("p1", Person.class));
        for (int i = 0; i < kv.length; i += 2) {
            p.getAttributes().putAttribute(kv[i], kv[i + 1]);
        }
        return p;
    }

    @Test
    void carOwnedButNoLicenceDoesNotClaimDrivableCar() {
        // The 8358_1 case: car_avail=always but hasLicense=false.
        String lines = PersonaPromptBuilder.composePersonaLines(
                personWith("carAvail", "always", "hasLicense", "false"));
        assertFalse(lines.contains("use a car"),
                () -> "must not claim a usable car without a licence:\n" + lines);
        assertFalse(lines.contains("car and can drive"),
                () -> "must not claim driving without a licence:\n" + lines);
        assertTrue(lines.contains("you don't have a driver's license"),
                () -> "should state the missing licence:\n" + lines);
    }

    @Test
    void carOwnedWithLicenceCanDrive() {
        String lines = PersonaPromptBuilder.composePersonaLines(
                personWith("carAvail", "always", "hasLicense", "true"));
        assertTrue(lines.contains("can drive it whenever you want"),
                () -> "licensed car owner should be able to drive:\n" + lines);
        // No contradictory "you don't have a driver's license" line.
        assertFalse(lines.contains("don't have a driver's license"), lines);
    }

    @Test
    void noCarNoLicence() {
        String lines = PersonaPromptBuilder.composePersonaLines(
                personWith("carAvail", "never", "hasLicense", "false"));
        assertTrue(lines.contains("don't have a car"), lines);
        assertTrue(lines.contains("don't have a driver's license"), lines);
    }

    @Test
    void licenceUnmodelledFallsBackToPlainCarLine() {
        // No hasLicense attribute at all -> keep the simple car statement.
        String lines = PersonaPromptBuilder.composePersonaLines(
                personWith("carAvail", "always"));
        assertTrue(lines.contains("car you can use whenever you want"), lines);
        assertFalse(lines.contains("driver's license"), lines);
    }

    @Test
    void compactTaskPromptDropsPlanJsonAndTerseDropsTalkThrough() {
        String plan = "{\"elements\":[]}";
        String block = "What you already know: route options ...";
        String full = PersonaPromptBuilder.buildTaskPrompt(plan, block, "decide_trips");
        assertTrue(full.contains(plan) && full.contains(block) && full.contains("Talk through your choices"));
        String compact = PersonaPromptBuilder.buildTaskPrompt(plan, block, "decide_trips", true, false);
        assertFalse(compact.contains(plan), "plan JSON left out");
        assertTrue(compact.startsWith(block) && compact.contains("call decide_trips"));
        String terse = PersonaPromptBuilder.buildTaskPrompt(plan, block, "decide_trips", true, true);
        assertFalse(terse.contains("Talk through your choices"));
        assertTrue(terse.endsWith(IndividualPrompt.personaTerseClosing));
        // compact without a block falls back to the plan JSON rather than an empty message
        assertTrue(PersonaPromptBuilder.buildTaskPrompt(plan, "", "decide_trips", true, false).contains(plan));
    }

    @Test
    void terseSystemPromptReplacesTalkThroughGuidance() {
        Person p = personWith("age", "40");
        String talk = PersonaPromptBuilder.buildSystemPrompt(p, true, true, false);
        String terse = PersonaPromptBuilder.buildSystemPrompt(p, true, true, true);
        assertTrue(talk.contains("Talk through it out loud"));
        assertFalse(terse.contains("Talk through it out loud"));
        assertTrue(terse.contains("one or two short sentences"));
        assertFalse(terse.contains("{{"), "no unfilled template slot");
        assertFalse(talk.contains("{{"), "no unfilled template slot");
    }
}
