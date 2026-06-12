package tools.Implement;

import java.util.HashMap;
import java.util.Map;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import rag.IVectorDB;
import tools.DefaultToolResponse;
import tools.ErrorMessages;
import tools.ITool;
import tools.IToolResponse;
import tools.ToolArgument;
import tools.ToolArgumentDTO;
import tools.VerificationFailedException;

/**
 * Returns a simplified view of the person's current plan, filtering out
 * PT interaction activities and route details. This eliminates the LLM's
 * need to parse complex PT chains to identify real activities.
 *
 * The LLM should call this tool first to understand the plan structure
 * before making routing decisions.
 */
public class ActivityChainSummaryTool implements ITool<String> {

    private final Map<String, ToolArgument<?, ? extends ToolArgumentDTO<?>>> arguments = new HashMap<>();

    // No arguments needed — reads the person's plan from context

    @Override
    public String getName() {
        return "activity_chain_summary";
    }

    @Override
    public Class<String> getOutputClass() {
        return String.class;
    }

    @Override
    public String getDescription() {
        return "Returns a simplified summary of the person's current daily plan. "
             + "Shows only real activities (home, work, shopping, etc.) and trip segments between them, "
             + "filtering out PT interaction activities and route details. "
             + "Call this tool FIRST to understand the plan structure before making routing decisions. "
             + "Each trip segment shows its current mode and the origin/destination facility IDs needed for the router_tool.";
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
    public JsonObject getJsonSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("name", getName());
        schema.addProperty("description", getDescription());

        // Empty parameters — tool takes no arguments
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", new JsonObject());
        parameters.add("required", new JsonArray());

        schema.add("parameters", parameters);
        return schema;
    }

    @Override
    public IToolResponse<String> callTool(String id, Map<String, Object> arguments,
                                          IVectorDB vectorDB, Map<String, Object> contextObject) {

        Person person = getPersonFromContext(contextObject);
        Plan plan = person.getSelectedPlan();

        if (plan == null || plan.getPlanElements() == null || plan.getPlanElements().isEmpty()) {
            JsonObject error = new JsonObject();
            error.addProperty("status", "error");
            error.addProperty("message", "Person has no selected plan or plan is empty.");
            return new DefaultToolResponse<>(id, getName(), error.toString(), "", false);
        }

        JsonObject response = new JsonObject();
        response.addProperty("personId", person.getId().toString());

        JsonArray chain = new JsonArray();
        int tripNumber = 0;
        String lastRealFacilityId = null;
        String currentTripMode = null;
        boolean inTrip = false;

        for (PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Activity) {
                Activity act = (Activity) pe;

                // Skip interaction activities (PT transfers, etc.)
                if (isInteractionActivity(act)) {
                    continue;
                }

                // If we were in a trip, close it
                if (inTrip && currentTripMode != null) {
                    JsonObject trip = new JsonObject();
                    trip.addProperty("type", "trip");
                    trip.addProperty("tripNumber", tripNumber);
                    trip.addProperty("currentMode", currentTripMode);
                    trip.addProperty("fromFacilityId", lastRealFacilityId != null ? lastRealFacilityId : "unknown");
                    trip.addProperty("toFacilityId", getFacilityId(act));
                    chain.add(trip);
                    inTrip = false;
                    currentTripMode = null;
                }

                // Add the real activity
                JsonObject actJson = new JsonObject();
                actJson.addProperty("type", "activity");
                actJson.addProperty("activityType", act.getType());
                actJson.addProperty("facilityId", getFacilityId(act));
                if (act.getLinkId() != null) {
                    actJson.addProperty("linkId", act.getLinkId().toString());
                }
                if (!Double.isInfinite(act.getEndTime().orElse(Double.POSITIVE_INFINITY))) {
                    double endTime = act.getEndTime().seconds();
                    actJson.addProperty("endTimeSeconds", endTime);
                    actJson.addProperty("endTimeFormatted", formatTime(endTime));
                }
                chain.add(actJson);

                lastRealFacilityId = getFacilityId(act);

            } else if (pe instanceof Leg) {
                Leg leg = (Leg) pe;

                // For PT chains, only capture the main routing mode
                if (!inTrip) {
                    tripNumber++;
                    inTrip = true;
                    currentTripMode = leg.getRoutingMode() != null ? leg.getRoutingMode() : leg.getMode();
                }
                // If already in a trip (PT chain), the first leg's routing mode captures the trip mode
            }
        }

        response.add("chain", chain);
        response.addProperty("totalRealActivities", countRealActivities(chain));
        response.addProperty("totalTrips", tripNumber);

        String responseJson = response.toString();
        return new DefaultToolResponse<>(id, getName(), responseJson, responseJson, false);
    }

    @Override
    public void verifyArguments(Map<String, Object> arguments, Map<String, Object> context,
                                ErrorMessages em) throws VerificationFailedException {
        if (context == null) {
            em.addErrorMessages("Tool context is null.");
            throw new VerificationFailedException(em.getErrorMessages());
        }

        Object personObj = context.get("person");
        if (!(personObj instanceof Person)) {
            em.addErrorMessages("Context does not contain a Person object under key 'person'.");
            throw new VerificationFailedException(em.getErrorMessages());
        }
    }

    private Person getPersonFromContext(Map<String, Object> contextObject) {
        Object obj = contextObject.get("person");
        if (!(obj instanceof Person)) {
            throw new RuntimeException(
                "ActivityChainSummaryTool requires Person in context under key 'person'."
            );
        }
        return (Person) obj;
    }

    private static boolean isInteractionActivity(Activity act) {
        if (act == null || act.getType() == null) return false;
        return act.getType().toLowerCase().contains("interaction");
    }

    private static String getFacilityId(Activity act) {
        if (act.getFacilityId() != null) {
            return act.getFacilityId().toString();
        }
        if (act.getLinkId() != null) {
            return "link:" + act.getLinkId().toString();
        }
        return "unknown";
    }

    private static String formatTime(double seconds) {
        int h = (int) (seconds / 3600);
        int m = (int) ((seconds % 3600) / 60);
        return String.format("%02d:%02d", h, m);
    }

    private static int countRealActivities(JsonArray chain) {
        int count = 0;
        for (var elem : chain) {
            if (elem.getAsJsonObject().get("type").getAsString().equals("activity")) {
                count++;
            }
        }
        return count;
    }
}
