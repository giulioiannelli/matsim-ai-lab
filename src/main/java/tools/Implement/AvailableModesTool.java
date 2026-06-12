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

import rag.IVectorDB;
import tools.DefaultToolResponse;
import tools.ErrorMessages;
import tools.ITool;
import tools.IToolResponse;
import tools.SimpleStringDTO;
import tools.ToolArgument;
import tools.ToolArgumentDTO;
import tools.VerificationFailedException;

/**
 * Pre-computes which transport modes the person can actually use at a given
 * facility, considering person attributes (license, car/bike availability)
 * and vehicle location tracking (where the car/bike currently is in the plan).
 *
 * This eliminates the LLM's need to reason about mode availability,
 * which accounts for ~21% of eliminable reasoning tokens.
 */
public class AvailableModesTool implements ITool<String> {

    private final Map<String, ToolArgument<?, ? extends ToolArgumentDTO<?>>> arguments = new HashMap<>();

    public AvailableModesTool() {
        registerArgument(SimpleStringDTO.forArgument("fromFacilityId"));
    }

    @Override
    public String getName() {
        return "available_modes";
    }

    @Override
    public Class<String> getOutputClass() {
        return String.class;
    }

    @Override
    public String getDescription() {
        return "Returns the list of transport modes available to this person at a given facility. "
             + "Considers the person's attributes (driving license, car ownership, bike availability) "
             + "and tracks vehicle locations by scanning the current plan. "
             + "For example, if the person drove to work, the car is at work — so car mode is available "
             + "at work but not at other locations. "
             + "Call this tool for each trip to know which modes you can choose from before calling router_tool.";
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

        String fromFacilityId = (String) arguments.get("fromFacilityId");
        Person person = getPersonFromContext(contextObject);
        Plan plan = person.getSelectedPlan();

        // Extract person attributes
        Map<String, Object> attrs = person.getAttributes().getAsMap();
        // Driving licences are not modelled in every scenario (e.g. Sioux Falls,
        // where car access is governed by carAvail + assigned vehicle). A missing
        // attribute must not be read as "unlicensed" — that would deny car to
        // every car-owning agent and contradict their own survey plans. Absent =>
        // assume licensed; explicit yes/no still honoured.
        boolean hasLicense = attrs.get("hasLicense") == null || decodeYesNo(attrs.get("hasLicense"));
        String carAvail = attrs.get("carAvail") != null ? attrs.get("carAvail").toString() : "never";
        String bikeAvail = attrs.get("bikeAvailability") != null ? attrs.get("bikeAvailability").toString() : "never";

        // Track vehicle locations by scanning the plan
        String carLocation = findVehicleLocation(plan, "car");
        String bikeLocation = findVehicleLocation(plan, "bike");

        // Determine the home facility (first activity, assumed to be home)
        String homeFacility = findHomeFacility(plan);

        // Build available modes list
        JsonArray modes = new JsonArray();

        // Walk is always available
        addMode(modes, "walk", true, "Walking is always available.");

        // PT is always available (MATSim handles access/egress)
        addMode(modes, "pt", true, "Public transport is always available.");

        // Car availability
        boolean carOwned = !"never".equalsIgnoreCase(carAvail);
        boolean carAtLocation = carLocation != null && normalizeFacility(carLocation).equals(normalizeFacility(fromFacilityId));
        // If no car legs in plan yet, car starts at home
        if (carLocation == null && carOwned && homeFacility != null
                && normalizeFacility(homeFacility).equals(normalizeFacility(fromFacilityId))) {
            carAtLocation = true;
        }

        if (!hasLicense) {
            addMode(modes, "car", false, "Person does not have a driving license.");
        } else if (!carOwned) {
            addMode(modes, "car", false, "Person has no car available (carAvail=" + carAvail + ").");
        } else if (!carAtLocation) {
            String carLocDesc = carLocation != null ? carLocation : homeFacility;
            addMode(modes, "car", false,
                "Car is at facility " + carLocDesc + ", not at " + fromFacilityId + ".");
        } else {
            addMode(modes, "car", true,
                "Car is at this location. Person has license and car available.");
        }

        // Bike availability
        boolean bikeOwned = !"never".equalsIgnoreCase(bikeAvail);
        boolean bikeAtLocation = bikeLocation != null && normalizeFacility(bikeLocation).equals(normalizeFacility(fromFacilityId));
        if (bikeLocation == null && bikeOwned && homeFacility != null
                && normalizeFacility(homeFacility).equals(normalizeFacility(fromFacilityId))) {
            bikeAtLocation = true;
        }

        if (!bikeOwned) {
            addMode(modes, "bike", false, "Person has no bike available (bikeAvailability=" + bikeAvail + ").");
        } else if (!bikeAtLocation) {
            String bikeLocDesc = bikeLocation != null ? bikeLocation : homeFacility;
            addMode(modes, "bike", false,
                "Bike is at facility " + bikeLocDesc + ", not at " + fromFacilityId + ".");
        } else {
            addMode(modes, "bike", true, "Bike is at this location.");
        }

        // Car passenger (always available if someone else can drive)
        addMode(modes, "car_passenger", true, "Car passenger mode is always available (ride with others).");

        // Build response
        JsonObject response = new JsonObject();
        response.addProperty("personId", person.getId().toString());
        response.addProperty("fromFacilityId", fromFacilityId);
        response.add("availableModes", modes);

        // Summary for quick LLM consumption
        List<String> availableNames = new ArrayList<>();
        for (var elem : modes) {
            JsonObject mode = elem.getAsJsonObject();
            if (mode.get("available").getAsBoolean()) {
                availableNames.add(mode.get("mode").getAsString());
            }
        }
        response.addProperty("summary", "Available modes at " + fromFacilityId + ": " + String.join(", ", availableNames));

        String responseJson = response.toString();
        return new DefaultToolResponse<>(id, getName(), responseJson, responseJson, false);
    }

    @Override
    public void verifyArguments(Map<String, Object> arguments, Map<String, Object> context,
                                ErrorMessages em) throws VerificationFailedException {

        List<String> errors = new ArrayList<>();

        if (arguments == null) {
            errors.add("Arguments map is null.");
        } else {
            String fromFacilityId = (String) arguments.get("fromFacilityId");
            if (fromFacilityId == null || fromFacilityId.trim().isEmpty()) {
                errors.add("Missing or empty fromFacilityId.");
            }
        }

        if (context == null) {
            errors.add("Tool context is null.");
        } else {
            Object personObj = context.get("person");
            if (!(personObj instanceof Person)) {
                errors.add("Context does not contain a Person object under key 'person'.");
            }
        }

        em.getErrorMessages().addAll(errors);
        if (!errors.isEmpty()) {
            throw new VerificationFailedException(errors);
        }
    }

    // --- Helper methods ---

    private Person getPersonFromContext(Map<String, Object> contextObject) {
        Object obj = contextObject.get("person");
        if (!(obj instanceof Person)) {
            throw new RuntimeException(
                "AvailableModesTool requires Person in context under key 'person'."
            );
        }
        return (Person) obj;
    }

    /**
     * Scan the plan to find where a vehicle (car or bike) currently is.
     * Tracks the vehicle by following legs: if a leg uses the vehicle mode,
     * the vehicle ends up at the destination activity's facility.
     *
     * Returns null if the vehicle hasn't been used (it's at home).
     */
    private static String findVehicleLocation(Plan plan, String vehicleMode) {
        if (plan == null || plan.getPlanElements() == null) return null;

        String location = null;
        boolean lastLegUsedVehicle = false;

        for (PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Leg) {
                Leg leg = (Leg) pe;
                String mode = leg.getRoutingMode() != null ? leg.getRoutingMode() : leg.getMode();
                lastLegUsedVehicle = vehicleMode.equals(mode);
            } else if (pe instanceof Activity && lastLegUsedVehicle) {
                Activity act = (Activity) pe;
                if (!isInteractionActivity(act)) {
                    location = getFacilityId(act);
                }
                // Don't reset lastLegUsedVehicle here — for PT chains,
                // the vehicle location is only updated at real activities
            }
        }

        return location;
    }

    /**
     * Find the home facility — first real activity in the plan.
     */
    private static String findHomeFacility(Plan plan) {
        if (plan == null || plan.getPlanElements() == null) return null;

        for (PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Activity) {
                Activity act = (Activity) pe;
                if (!isInteractionActivity(act)) {
                    return getFacilityId(act);
                }
            }
        }
        return null;
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

    private static String normalizeFacility(String facilityId) {
        if (facilityId == null) return "";
        return facilityId.trim();
    }

    private static boolean decodeYesNo(Object value) {
        if (value == null) return false;
        String s = value.toString().toLowerCase().trim();
        return "yes".equals(s) || "true".equals(s) || "1".equals(s);
    }

    private static void addMode(JsonArray modes, String mode, boolean available, String reason) {
        JsonObject m = new JsonObject();
        m.addProperty("mode", mode);
        m.addProperty("available", available);
        m.addProperty("reason", reason);
        modes.add(m);
    }
}
