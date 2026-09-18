package matsimBinding.oneshot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import tools.IToolResponse;
import tools.Implement.ActivityChainSummaryTool;
import tools.Implement.AvailableModesTool;
import tools.Implement.comparison.CompareRoutesTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Precomputes, before the first LLM call, the facts an agent otherwise gathers
 * through three or four tool rounds: the activity chain summary, the modes
 * available at each place it visits, and the route comparison for every trip.
 *
 * <p>The existing tools are invoked programmatically with the same context the
 * chat manager would give them, so the numbers are identical to what the model
 * would have obtained by calling the tools itself. The result is a text block
 * for the user prompt; see {@link #build(Plan, Map)}.
 */
public final class OneShotContextBuilder {

    private static final Logger log = LogManager.getLogger(OneShotContextBuilder.class);

    /** Modes worth comparing per trip; car_passenger and transit_walk carry no decision. */
    private static final List<String> COMPARED_MODES = List.of("car", "pt", "bike", "walk");
    private static final double DEFAULT_FIRST_DEPARTURE = 8 * 3600.0;
    private static final double DEFAULT_ACTIVITY_DURATION = 3600.0;

    private final ActivityChainSummaryTool summaryTool = new ActivityChainSummaryTool();
    private final AvailableModesTool modesTool = new AvailableModesTool();
    private final CompareRoutesTool compareTool = new CompareRoutesTool();

    /** One origin-destination pair between consecutive real activities. */
    public record Trip(String fromFacilityId, String toFacilityId, double departureTime, String currentMode) {}

    /**
     * @param plan    the plan being replanned (its person must be {@code context.get("person")})
     * @param context the chat manager's context (person, activityFacilities, tripRoutersProvider)
     * @return the "what you already know" block, or a short note if nothing could be computed
     */
    public String build(Plan plan, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("What you already know (looked up for you; the tools activity_chain_summary, "
                + "available_modes and compare_routes have already been run, do not call them again):\n\n");

        String summary = call(summaryTool, Map.of(), context);
        sb.append("Your day, activity by activity:\n").append(summary).append("\n\n");

        List<Trip> trips = enumerateTrips(plan);
        Set<String> places = new LinkedHashSet<>();
        for (Trip t : trips) { places.add(t.fromFacilityId()); }

        Map<String, List<String>> availableAt = new LinkedHashMap<>();
        sb.append("Modes you can use when leaving each place:\n");
        for (String place : places) {
            String modesJson = call(modesTool, Map.of("fromFacilityId", place), context);
            availableAt.put(place, availableModesFrom(modesJson));
            sb.append("- from ").append(place).append(": ").append(summaryLine(modesJson)).append("\n");
        }
        sb.append("\n");

        sb.append("Route options for each trip, as the network stands today "
                + "(travel time in seconds, distance in metres; pt includes transfers):\n");
        int n = 0;
        for (Trip t : trips) {
            n++;
            List<String> modes = new ArrayList<>();
            for (String m : COMPARED_MODES) {
                if (availableAt.getOrDefault(t.fromFacilityId(), List.of()).contains(m)) modes.add(m);
            }
            if (modes.isEmpty()) modes.add("walk");
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("fromFacilityId", t.fromFacilityId());
            args.put("toFacilityId", t.toFacilityId());
            args.put("modes", String.join(",", modes));
            args.put("departureTimeSeconds", t.departureTime());
            String cmp = call(compareTool, args, context);
            sb.append(String.format(Locale.ROOT, "- trip %d, %s -> %s, leaving %s, currently by %s: %s\n",
                    n, t.fromFacilityId(), t.toFacilityId(), clock(t.departureTime()), t.currentMode(), routesLine(cmp)));
        }
        return sb.toString();
    }

    /** Consecutive real activities (stage activities such as "pt interaction" are skipped). */
    public static List<Trip> enumerateTrips(Plan plan) {
        List<Activity> real = new ArrayList<>();
        List<String> modeBefore = new ArrayList<>();
        String lastMainMode = null;
        for (PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Activity act) {
                if (isStageActivity(act)) continue;
                real.add(act);
                modeBefore.add(lastMainMode);
                lastMainMode = null;
            } else if (pe instanceof org.matsim.api.core.v01.population.Leg leg) {
                String mode = leg.getRoutingMode() != null ? leg.getRoutingMode() : leg.getMode();
                if (lastMainMode == null || !"walk".equals(mode)) lastMainMode = mode;
            }
        }
        List<Trip> trips = new ArrayList<>();
        double clock = DEFAULT_FIRST_DEPARTURE;
        for (int i = 0; i + 1 < real.size(); i++) {
            Activity from = real.get(i);
            Activity to = real.get(i + 1);
            if (from.getEndTime().isDefined()) {
                clock = from.getEndTime().seconds();
            } else if (from.getMaximumDuration().isDefined()) {
                clock += from.getMaximumDuration().seconds();
            } else {
                clock += DEFAULT_ACTIVITY_DURATION;
            }
            String mode = modeBefore.get(i + 1) == null ? "?" : modeBefore.get(i + 1);
            trips.add(new Trip(facilityOf(from), facilityOf(to), clock, mode));
        }
        return trips;
    }

    static boolean isStageActivity(Activity act) {
        return act.getType() != null && act.getType().endsWith(" interaction");
    }

    private static String facilityOf(Activity act) {
        if (act.getFacilityId() != null) return act.getFacilityId().toString();
        return act.getLinkId() != null ? act.getLinkId().toString() : "?";
    }

    private static String clock(double seconds) {
        int s = (int) Math.round(seconds);
        return String.format(Locale.ROOT, "%02d:%02d", s / 3600, (s % 3600) / 60);
    }

    private static String call(tools.ITool<?> tool, Map<String, Object> args, Map<String, Object> context) {
        try {
            IToolResponse<?> r = tool.callTool("precomputed-" + tool.getName(), args, null, context);
            return r == null || r.getResponseJson() == null ? "(unavailable)" : r.getResponseJson();
        } catch (Exception e) {
            log.warn("Precomputed {} failed: {}", tool.getName(), e.toString());
            return "(unavailable: " + e.getMessage() + ")";
        }
    }

    static List<String> availableModesFrom(String modesJson) {
        List<String> out = new ArrayList<>();
        try {
            JsonObject o = JsonParser.parseString(modesJson).getAsJsonObject();
            JsonArray arr = o.getAsJsonArray("availableModes");
            if (arr == null) return out;
            for (JsonElement e : arr) {
                JsonObject m = e.getAsJsonObject();
                if (m.has("available") && m.get("available").getAsBoolean()) out.add(m.get("mode").getAsString());
            }
        } catch (Exception ignored) {
            // malformed: compare nothing rather than fail the query
        }
        return out;
    }

    private static String summaryLine(String modesJson) {
        try {
            JsonObject o = JsonParser.parseString(modesJson).getAsJsonObject();
            if (o.has("summary")) return o.get("summary").getAsString().replaceFirst("^Available modes at [^:]+: ", "");
        } catch (Exception ignored) { }
        return modesJson;
    }

    private static String routesLine(String compareJson) {
        try {
            JsonObject o = JsonParser.parseString(compareJson).getAsJsonObject();
            JsonArray routes = o.getAsJsonArray("routes");
            if (routes == null) return compareJson;
            List<String> parts = new ArrayList<>();
            for (JsonElement e : routes) parts.add(e.toString());
            return String.join(" ", parts);
        } catch (Exception ignored) { }
        return compareJson;
    }
}
