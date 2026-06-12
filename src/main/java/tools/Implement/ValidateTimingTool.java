package tools.Implement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import matsimdtobjects.PlanDTO;
import rag.IVectorDB;
import tools.DefaultToolResponse;
import tools.ErrorMessages;
import tools.ITool;
import tools.IToolResponse;
import tools.ToolArgument;
import tools.ToolArgumentDTO;
import tools.VerificationFailedException;

/**
 * Validates the temporal consistency and structural correctness of a plan
 * before it is submitted via extract_plan. Returns specific error messages
 * that the LLM can use to fix problems.
 *
 * Checks performed:
 * - Plan starts and ends with an activity
 * - Activities and legs alternate correctly
 * - No real activities were dropped compared to the original plan
 * - Activity types and locations match the original
 * - Departure times are non-negative and in increasing order
 *
 * The LLM should call this tool BEFORE extract_plan to catch errors early.
 */
public class ValidateTimingTool implements ITool<String> {

    private final Map<String, ToolArgument<?, ? extends ToolArgumentDTO<?>>> arguments = new HashMap<>();

    public ValidateTimingTool() {
        registerArgument(
            new ToolArgument<>(
                "plan",
                PlanDTO.class,
                PlanDTO.toDTOFromBaseObject(),
                PlanDTO.getJsonSchema()
            )
        );
    }

    @Override
    public String getName() {
        return "validate_timing";
    }

    @Override
    public Class<String> getOutputClass() {
        return String.class;
    }

    @Override
    public String getDescription() {
        return "Validates a plan for temporal consistency and structural correctness BEFORE submitting it via extract_plan. "
             + "Checks that: the plan starts and ends with activities, activities and legs alternate, "
             + "no real activities were dropped or reordered compared to the original, "
             + "and timing is consistent. "
             + "Returns {valid: true} or {valid: false, errors: [...]} with specific error messages you can fix. "
             + "Call this tool BEFORE extract_plan to avoid failed plan submissions.";
    }

    @Override
    public boolean isDummy() {
        return false;
    }

    @Override
    public Map<String, ToolArgument<?, ? extends ToolArgumentDTO<?>>> getRegisteredArguments() {
        return arguments;
    }

    @Override
    public IToolResponse<String> callTool(String id, Map<String, Object> arguments,
                                          IVectorDB vectorDB, Map<String, Object> contextObject) {

        Plan candidatePlan = (Plan) arguments.get("plan");
        Person person = getPersonFromContext(contextObject);
        Plan originalPlan = person.getSelectedPlan();

        List<String> errors = new ArrayList<>();

        // --- Structural checks ---
        List<PlanElement> elements = candidatePlan.getPlanElements();

        if (elements == null || elements.isEmpty()) {
            errors.add("Plan has no elements.");
        } else {
            // First element must be an activity
            if (!(elements.get(0) instanceof Activity)) {
                errors.add("Plan must start with an activity, but starts with a "
                    + elements.get(0).getClass().getSimpleName() + ".");
            }

            // Last element must be an activity
            if (!(elements.get(elements.size() - 1) instanceof Activity)) {
                errors.add("Plan must end with an activity, but ends with a "
                    + elements.get(elements.size() - 1).getClass().getSimpleName() + ".");
            }

            // Check alternation: Activity, Leg, Activity, Leg, ..., Activity
            for (int i = 0; i < elements.size(); i++) {
                boolean shouldBeActivity = (i % 2 == 0);
                PlanElement pe = elements.get(i);
                if (shouldBeActivity && !(pe instanceof Activity)) {
                    errors.add("Element at position " + i + " should be an Activity but is a "
                        + pe.getClass().getSimpleName() + ".");
                } else if (!shouldBeActivity && !(pe instanceof Leg)) {
                    errors.add("Element at position " + i + " should be a Leg but is a "
                        + pe.getClass().getSimpleName() + ".");
                }
            }
        }

        // --- Activity consistency with original plan ---
        if (originalPlan != null) {
            List<RealActivity> originalActivities = extractRealActivities(originalPlan);
            List<RealActivity> candidateActivities = extractRealActivities(candidatePlan);

            if (originalActivities.size() != candidateActivities.size()) {
                errors.add("Original plan has " + originalActivities.size()
                    + " real activities but candidate has " + candidateActivities.size()
                    + ". Do not add or drop activities.");
            }

            int compareCount = Math.min(originalActivities.size(), candidateActivities.size());
            for (int i = 0; i < compareCount; i++) {
                RealActivity orig = originalActivities.get(i);
                RealActivity cand = candidateActivities.get(i);

                if (!orig.type.equals(cand.type)) {
                    errors.add("Activity " + i + ": expected type '" + orig.type
                        + "' but got '" + cand.type + "'.");
                }

                if (orig.locationKey != null && cand.locationKey != null
                        && !orig.locationKey.equals(cand.locationKey)) {
                    errors.add("Activity " + i + " (" + orig.type
                        + "): expected location '" + orig.locationKey
                        + "' but got '" + cand.locationKey + "'.");
                }
            }
        }

        // --- Timing checks ---
        double lastTime = -1;
        for (PlanElement pe : elements != null ? elements : List.<PlanElement>of()) {
            if (pe instanceof Leg) {
                Leg leg = (Leg) pe;
                double depTime = leg.getDepartureTime().orElse(Double.NaN);
                if (!Double.isNaN(depTime)) {
                    if (depTime < 0) {
                        errors.add("Leg has negative departure time: " + depTime);
                    }
                    if (lastTime >= 0 && depTime < lastTime) {
                        errors.add("Leg departure time " + depTime
                            + "s is before previous time " + lastTime + "s (time goes backwards).");
                    }
                    lastTime = depTime;

                    double travelTime = leg.getTravelTime().orElse(0);
                    if (travelTime > 0) {
                        lastTime = depTime + travelTime;
                    }
                }
            } else if (pe instanceof Activity) {
                Activity act = (Activity) pe;
                double endTime = act.getEndTime().orElse(Double.NaN);
                if (!Double.isNaN(endTime)) {
                    if (endTime < 0) {
                        errors.add("Activity '" + act.getType() + "' has negative end time: " + endTime);
                    }
                    lastTime = endTime;
                }
            }
        }

        // --- Build response ---
        JsonObject response = new JsonObject();
        if (errors.isEmpty()) {
            response.addProperty("valid", true);
            response.addProperty("message", "Plan is valid. You can now call extract_plan.");
        } else {
            response.addProperty("valid", false);
            JsonArray errorArray = new JsonArray();
            for (String error : errors) {
                errorArray.add(error);
            }
            response.add("errors", errorArray);
            response.addProperty("message", "Plan has " + errors.size()
                + " error(s). Fix them and call validate_timing again before extract_plan.");
        }

        String responseJson = response.toString();
        return new DefaultToolResponse<>(id, getName(), responseJson, responseJson, false);
    }

    @Override
    public void verifyArguments(Map<String, Object> arguments, Map<String, Object> context,
                                ErrorMessages em) throws VerificationFailedException {

        int numErrors = 0;

        if (arguments == null) {
            em.addErrorMessages("Arguments map is null.");
            numErrors++;
        } else {
            Object planObj = arguments.get("plan");
            if (planObj == null) {
                em.addErrorMessages("Missing required argument: plan.");
                numErrors++;
            } else if (!(planObj instanceof Plan)) {
                em.addErrorMessages("Argument 'plan' is not a MATSim Plan.");
                numErrors++;
            }
        }

        if (context == null) {
            em.addErrorMessages("Tool context is null.");
            numErrors++;
        } else {
            Object personObj = context.get("person");
            if (!(personObj instanceof Person)) {
                em.addErrorMessages("Context does not contain a Person object under key 'person'.");
                numErrors++;
            }
        }

        if (numErrors > 0) {
            throw new VerificationFailedException(em.getErrorMessages());
        }
    }

    // --- Helper methods ---

    private Person getPersonFromContext(Map<String, Object> contextObject) {
        Object obj = contextObject.get("person");
        if (!(obj instanceof Person)) {
            throw new RuntimeException(
                "ValidateTimingTool requires Person in context under key 'person'."
            );
        }
        return (Person) obj;
    }

    private static List<RealActivity> extractRealActivities(Plan plan) {
        List<RealActivity> out = new ArrayList<>();
        for (PlanElement pe : plan.getPlanElements()) {
            if (!(pe instanceof Activity)) continue;
            Activity act = (Activity) pe;
            if (isInteractionActivity(act)) continue;
            out.add(new RealActivity(act.getType(), getLocationKey(act)));
        }
        return out;
    }

    private static boolean isInteractionActivity(Activity act) {
        if (act == null || act.getType() == null) return false;
        return act.getType().toLowerCase().contains("interaction");
    }

    private static String getLocationKey(Activity act) {
        if (act.getFacilityId() != null) {
            return "facility:" + act.getFacilityId();
        }
        if (act.getLinkId() != null) {
            return "link:" + act.getLinkId();
        }
        return "none";
    }

    private static final class RealActivity {
        final String type;
        final String locationKey;

        RealActivity(String type, String locationKey) {
            this.type = type;
            this.locationKey = locationKey;
        }
    }
}
