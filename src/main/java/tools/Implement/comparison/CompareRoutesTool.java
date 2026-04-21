package tools.Implement.comparison;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.router.TripRouter;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provider;

import rag.IVectorDB;
import tools.DefaultToolResponse;
import tools.ErrorMessages;
import tools.ITool;
import tools.IToolResponse;
import tools.SimpleDoubleDTO;
import tools.SimpleStringDTO;
import tools.ToolArgument;
import tools.ToolArgumentDTO;
import tools.VerificationFailedException;

/**
 * Routes the same origin/destination/departure under several modes and returns
 * a compact comparison table. The LLM is expected to read the table and
 * express a preference; the tool never picks a winner itself.
 */
public class CompareRoutesTool implements ITool<String> {

    private static final List<String> ALLOWED_MODES = Arrays.asList(
            "car", "pt", "car_passenger", "bike", "walk", "transit_walk");

    private final Map<String, ToolArgument<?, ? extends ToolArgumentDTO<?>>> arguments = new HashMap<>();

    public CompareRoutesTool() {
        registerArgument(SimpleStringDTO.forArgument("fromFacilityId"));
        registerArgument(SimpleStringDTO.forArgument("toFacilityId"));
        registerArgument(SimpleStringDTO.forArgument("modes"));
        registerArgument(SimpleDoubleDTO.forArgument("departureTimeSeconds"));
    }

    @Override
    public String getName() {
        return "compare_routes";
    }

    @Override
    public Class<String> getOutputClass() {
        return String.class;
    }

    @Override
    public String getDescription() {
        return "Compares several transport modes on the same origin-destination at a given "
             + "departure time. Returns a table of routes with travel time, distance, and "
             + "transfer count for each requested mode. Does not choose for you — read the "
             + "numbers and decide. The 'modes' argument is a comma-separated string like "
             + "\"car,pt,bike\". Supported modes: car, pt, car_passenger, bike, walk.";
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

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        properties.add("fromFacilityId", SimpleStringDTO.STATIC_SCHEMA);
        properties.add("toFacilityId", SimpleStringDTO.STATIC_SCHEMA);
        properties.add("modes", SimpleStringDTO.STATIC_SCHEMA);
        properties.add("departureTimeSeconds", SimpleDoubleDTO.STATIC_SCHEMA);

        JsonArray required = new JsonArray();
        required.add("fromFacilityId");
        required.add("toFacilityId");
        required.add("modes");
        required.add("departureTimeSeconds");

        parameters.add("properties", properties);
        parameters.add("required", required);
        schema.add("parameters", parameters);
        return schema;
    }

    @Override
    public IToolResponse<String> callTool(String id, Map<String, Object> args,
                                          IVectorDB vectorDB, Map<String, Object> context) {
        String fromId = (String) args.get("fromFacilityId");
        String toId = (String) args.get("toFacilityId");
        String modesRaw = (String) args.get("modes");
        Double depTime = (Double) args.get("departureTimeSeconds");
        if (depTime == null) depTime = 0.0;

        ActivityFacilities facilities = facilitiesFromContext(context);
        Provider<TripRouter> routerProvider = routerFromContext(context);
        Person person = personFromContext(context);

        ActivityFacility from = facilities.getFacilities().get(
                Id.create(fromId.trim(), ActivityFacility.class));
        ActivityFacility to = facilities.getFacilities().get(
                Id.create(toId.trim(), ActivityFacility.class));

        List<String> modes = parseModes(modesRaw);
        List<RouteMetrics> results = new ArrayList<>(modes.size());

        for (String mode : modes) {
            if (!ALLOWED_MODES.contains(mode)) {
                results.add(RouteMetrics.infeasible(mode, "unsupported mode"));
                continue;
            }
            try {
                List<? extends PlanElement> routed = routerProvider.get()
                        .calcRoute(mode, from, to, depTime, person, null);
                if (routed == null || routed.isEmpty()) {
                    results.add(RouteMetrics.infeasible(mode, "no route"));
                    continue;
                }
                results.add(summarise(mode, routed));
            } catch (Exception e) {
                results.add(RouteMetrics.infeasible(mode, "routing failed: " + e.getMessage()));
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("fromFacilityId", fromId);
        response.addProperty("toFacilityId", toId);
        response.addProperty("departureTimeSeconds", depTime);

        JsonArray table = new JsonArray();
        for (RouteMetrics rm : results) table.add(rm.toJson());
        response.add("routes", table);

        String responseJson = response.toString();
        return new DefaultToolResponse<>(id, getName(), responseJson, responseJson, false);
    }

    @Override
    public void verifyArguments(Map<String, Object> args, Map<String, Object> context,
                                ErrorMessages em) throws VerificationFailedException {
        List<String> errors = new ArrayList<>();

        if (args == null) {
            errors.add("Arguments map is null.");
        } else {
            String fromId = (String) args.get("fromFacilityId");
            String toId = (String) args.get("toFacilityId");
            String modesRaw = (String) args.get("modes");
            Double depTime = (Double) args.get("departureTimeSeconds");

            if (fromId == null || fromId.trim().isEmpty()) errors.add("Missing fromFacilityId.");
            if (toId == null || toId.trim().isEmpty()) errors.add("Missing toFacilityId.");
            if (modesRaw == null || modesRaw.trim().isEmpty())
                errors.add("Missing modes (expected comma-separated list).");
            if (depTime != null && depTime < 0) errors.add("departureTimeSeconds cannot be negative.");

            if (context == null) {
                errors.add("Tool context is null.");
            } else {
                if (context.get("tripRoutersProvider") == null)
                    errors.add("Context missing 'tripRoutersProvider'.");
                Object facObj = firstPresent(context, "activityFacilities", "facilities");
                if (!(facObj instanceof ActivityFacilities)) {
                    errors.add("Context missing ActivityFacilities under 'activityFacilities'.");
                } else {
                    ActivityFacilities facs = (ActivityFacilities) facObj;
                    if (fromId != null && !fromId.trim().isEmpty()
                            && !facs.getFacilities().containsKey(Id.create(fromId.trim(), ActivityFacility.class))) {
                        errors.add("Origin facilityId not found: " + fromId);
                    }
                    if (toId != null && !toId.trim().isEmpty()
                            && !facs.getFacilities().containsKey(Id.create(toId.trim(), ActivityFacility.class))) {
                        errors.add("Destination facilityId not found: " + toId);
                    }
                }
            }
        }

        em.getErrorMessages().addAll(errors);
        if (!errors.isEmpty()) throw new VerificationFailedException(errors);
    }

    static List<String> parseModes(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String cleaned = raw.trim();
        // LLMs sometimes wrap the value in quotes or brackets: "walk,pt" or ["walk","pt"].
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        for (String part : cleaned.split(",")) {
            String trimmed = part.trim();
            // Strip matching single/double quotes the LLM may have left around each item.
            if (trimmed.length() >= 2) {
                char c0 = trimmed.charAt(0), cN = trimmed.charAt(trimmed.length() - 1);
                if ((c0 == '"' || c0 == '\'') && c0 == cN) {
                    trimmed = trimmed.substring(1, trimmed.length() - 1);
                }
            }
            // Drop any remaining stray quote characters (handles unbalanced wrapping).
            trimmed = trimmed.replace("\"", "").replace("'", "").trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && !out.contains(trimmed)) out.add(trimmed);
        }
        return out;
    }

    static RouteMetrics summarise(String mode, List<? extends PlanElement> routed) {
        double totalTime = 0.0;
        double totalDist = 0.0;
        int transfers = 0;
        int ptLegs = 0;
        for (PlanElement pe : routed) {
            if (!(pe instanceof Leg leg)) continue;
            totalTime += leg.getTravelTime().orElse(0.0);
            if (leg.getRoute() != null) totalDist += leg.getRoute().getDistance();
            String legMode = leg.getRoutingMode() != null ? leg.getRoutingMode() : leg.getMode();
            if ("pt".equals(legMode)) ptLegs++;
        }
        if ("pt".equals(mode)) transfers = Math.max(0, ptLegs - 1);
        return new RouteMetrics(mode, totalTime, totalDist, transfers, true, null);
    }

    private static ActivityFacilities facilitiesFromContext(Map<String, Object> ctx) {
        Object obj = firstPresent(ctx, "activityFacilities", "facilities");
        if (!(obj instanceof ActivityFacilities)) {
            throw new RuntimeException("CompareRoutesTool requires ActivityFacilities in context.");
        }
        return (ActivityFacilities) obj;
    }

    @SuppressWarnings("unchecked")
    private static Provider<TripRouter> routerFromContext(Map<String, Object> ctx) {
        Object obj = ctx.get("tripRoutersProvider");
        if (obj == null) throw new RuntimeException("CompareRoutesTool requires 'tripRoutersProvider' in context.");
        return (Provider<TripRouter>) obj;
    }

    private static Person personFromContext(Map<String, Object> ctx) {
        Object obj = ctx.get("person");
        return obj instanceof Person ? (Person) obj : null;
    }

    private static Object firstPresent(Map<String, Object> ctx, String... keys) {
        if (ctx == null) return null;
        for (String k : keys) if (ctx.containsKey(k)) return ctx.get(k);
        return null;
    }
}
