package matsimdtobjects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.ErrorMessages;

/**
 * Verification accepts any non-blank, free-form activity type (as MATSim does),
 * rather than a hardcoded vocabulary that wrongly rejected real scenario types
 * such as Sioux Falls "secondary".
 */
class ActivityDTOVerifyTest {

    private static ActivityDTO activity(String type, String facilityId) {
        ActivityDTO a = new ActivityDTO();
        a.elementType = "activity";
        a.type = type;
        a.facilityId = facilityId;
        return a;
    }

    @Test
    void scenarioTypeSecondaryIsAccepted() {
        ErrorMessages em = new ErrorMessages();
        assertTrue(activity("secondary", "8007_16").isVerified(em),
                () -> "secondary should verify, got: " + em.getCombinedErrorMessages());
        assertTrue(em.isEmpty());
    }

    @Test
    void arbitraryFreeFormTypeIsAccepted() {
        ErrorMessages em = new ErrorMessages();
        assertTrue(activity("grocery_shopping", "1_1").isVerified(em),
                () -> "free-form type should verify, got: " + em.getCombinedErrorMessages());
    }

    @Test
    void commonTypesStillAccepted() {
        for (String t : new String[] {"home", "work", "education", "leisure"}) {
            ErrorMessages em = new ErrorMessages();
            assertTrue(activity(t, "1_1").isVerified(em),
                    () -> t + " should verify, got: " + em.getCombinedErrorMessages());
        }
    }

    @Test
    void blankTypeIsRejected() {
        assertFalse(activity("  ", "1_1").isVerified(new ErrorMessages()));
        assertFalse(activity(null, "1_1").isVerified(new ErrorMessages()));
    }

    @Test
    void missingLocationIsRejected() {
        // No facilityId and no linkId.
        assertFalse(activity("home", null).isVerified(new ErrorMessages()));
    }
}
