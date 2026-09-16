package matsimdtobjects;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.matsim.api.core.v01.population.Activity;
import tools.ErrorMessages;

import java.util.HashMap;

/**
 * A plan round-tripped through the DTOs must keep stage activities executable:
 * MATSim's router creates "pt interaction" with maximumDuration = 0, and an
 * interaction activity with neither end time nor maximum duration never ends
 * in the mobsim (the agent stays at the stop for the rest of the day).
 */
public class ActivityDTOStageActivityTest {

    private static ActivityDTO dto(String type, String linkId, Double endTime) {
        ActivityDTO d = new ActivityDTO();
        d.type = type;
        d.linkId = linkId;
        d.endTime = endTime;
        return d;
    }

    @Test
    public void ptInteractionWithoutEndTimeGetsZeroMaximumDuration() {
        ErrorMessages em = new ErrorMessages();
        Activity act = dto("pt interaction", "2_9", null).toBaseClass(new HashMap<>(), em);
        assertNotNull(act);
        assertTrue(em.isEmpty(), em.getCombinedErrorMessages());
        assertTrue(act.getMaximumDuration().isDefined());
        assertEquals(0.0, act.getMaximumDuration().seconds(), 1e-9);
        assertFalse(act.getEndTime().isDefined());
    }

    @Test
    public void otherInteractionTypesAreStageActivitiesToo() {
        Activity act = dto("car interaction", "2_9", null).toBaseClass(new HashMap<>(), new ErrorMessages());
        assertEquals(0.0, act.getMaximumDuration().seconds(), 1e-9);
        assertTrue(ActivityDTO.isStageActivityType("bike interaction"));
        assertFalse(ActivityDTO.isStageActivityType("work"));
    }

    @Test
    public void realActivityKeepsEndTimeAndNoMaximumDuration() {
        Activity act = dto("work", "9_2", 61821.0).toBaseClass(new HashMap<>(), new ErrorMessages());
        assertEquals(61821.0, act.getEndTime().seconds(), 1e-9);
        assertFalse(act.getMaximumDuration().isDefined());
    }

    @Test
    public void lastActivityWithoutEndTimeStaysOpenEnded() {
        Activity act = dto("home", "2_9", null).toBaseClass(new HashMap<>(), new ErrorMessages());
        assertFalse(act.getEndTime().isDefined());
        assertFalse(act.getMaximumDuration().isDefined());
    }
}
