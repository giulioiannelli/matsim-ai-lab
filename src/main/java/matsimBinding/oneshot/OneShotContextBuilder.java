package matsimBinding.oneshot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import tools.IToolResponse;
import tools.Implement.ActivityChainSummaryTool;
import tools.Implement.AvailableModesTool;
import tools.Implement.comparison.CompareRoutesTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Precomputes, before the first LLM call, the facts an agent otherwise gathers
 * through three or four tool rounds: the activity chain summary, which vehicles
 * it has and where they start the day, and the route comparison for every trip
 * (vehicle modes listed wherever an unbroken chain of trips can bring the
 * vehicle there, with the condition spelled out).
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
    private static final List<String> VEHICLE_MODES = List.of("car", "bike");
    private static final String VEHICLE_RULE =
            "Walk, pt and car_passenger work from anywhere. A car or bike is usable on a trip only if it is with you: "
            + "it starts where it is parked and moves with you on every trip you make with it. Driving out and driving "
            + "back is fine; going out by pt and driving back is not.";
    private static final double DEFAULT_FIRST_DEPARTURE = 8 * 3600.0;
    private static final double DEFAULT_ACTIVITY_DURATION = 3600.0;

    private final ActivityChainSummaryTool summaryTool = new ActivityChainSummaryTool();
    private final boolean compact;
    private final CompareRoutesTool compareTool = new CompareRoutesTool();

    public OneShotContextBuilder() { this(false); }

    /** @param compact route options in minutes and kilometres instead of raw JSON */
    public OneShotContextBuilder(boolean compact) { this.compact = compact; }

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
        Person person = context.get("person") instanceof Person p ? p : plan.getPerson();

        sb.append("Your vehicles:\n");
        Map<String, String[]> requirements = new LinkedHashMap<>();
        for (String vehicle : VEHICLE_MODES) {
            AvailableModesTool.VehicleAccess access = person == null
                    ? new AvailableModesTool.VehicleAccess(false, null)
                    : AvailableModesTool.vehicleAccess(person, plan, vehicle);
            if (access.owned() && access.startLocation() != null) {
                sb.append("- ").append(vehicle).append(": you have one, parked at ")
                  .append(access.startLocation()).append(" at the start of the day.\n");
                requirements.put(vehicle, vehicleRequirements(trips, access.startLocation()));
            } else {
                sb.append("- ").append(vehicle).append(": none available.\n");
            }
        }
        sb.append(VEHICLE_RULE).append("\n\n");

        sb.append("Route options for each trip, as the network stands today "
                + (compact ? "(" : "(travel time in seconds, distance in metres; ")
                + "pt includes transfers; "
                + "\"requires\" names the earlier trips you must drive or ride as well so the vehicle is with you):\n");
        int n = 0;
        for (Trip t : trips) {
            List<String> modes = new ArrayList<>();
            Map<String, String> requires = new LinkedHashMap<>();
            for (String m : COMPARED_MODES) {
                String[] req = requirements.get(m);
                if (req == null) {
                    if (!VEHICLE_MODES.contains(m)) modes.add(m);
                } else if (req[n] != null) {
                    modes.add(m);
                    if (!req[n].isEmpty()) requires.put(m, req[n]);
                }
            }
            n++;
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("fromFacilityId", t.fromFacilityId());
            args.put("toFacilityId", t.toFacilityId());
            args.put("modes", String.join(",", modes));
            args.put("departureTimeSeconds", t.departureTime());
            String cmp = call(compareTool, args, context);
            sb.append(String.format(Locale.ROOT, "- trip %d, %s -> %s, leaving %s, currently by %s: %s\n",
                    n, t.fromFacilityId(), t.toFacilityId(), clock(t.departureTime()), t.currentMode(),
                    compact ? compactRoutesLine(cmp, requires) : routesLine(cmp, requires)));
        }
        return sb.toString();
    }

    /**
     * For each trip, what it takes to have a vehicle at the trip's origin:
     * {@code null} when impossible, {@code ""} when the vehicle is parked there
     * already, otherwise the earlier trips that must also be made with it
     * (e.g. "drive trip 1 as well"). The vehicle starts at {@code startLocation}
     * and moves with the traveller on every trip made with it; it is back at a
     * trip's origin whenever the chain of trips since it was last parked there
     * is unbroken.
     */
    static String[] vehicleRequirements(List<Trip> trips, String startLocation) {
        String[] out = new String[trips.size()];
        int chainStart = -1;
        for (int i = 0; i < trips.size(); i++) {
            if (startLocation.equals(trips.get(i).fromFacilityId())) {
                chainStart = i;
                out[i] = "";
            } else if (chainStart >= 0) {
                out[i] = chainStart == i - 1
                        ? "trip " + (chainStart + 1) + " as well"
                        : "trips " + (chainStart + 1) + "-" + i + " as well";
            } else {
                out[i] = null;
            }
        }
        return out;
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

    /** "car 35 min, 4.9 km; pt 26 min, 2.3 km, 1 transfers; walk 57 min, 2.9 km" (infeasible options dropped). */
    static String compactRoutesLine(String compareJson, Map<String, String> requires) {
        try {
            JsonObject o = JsonParser.parseString(compareJson).getAsJsonObject();
            JsonArray routes = o.getAsJsonArray("routes");
            if (routes == null) return compareJson;
            List<String> parts = new ArrayList<>();
            for (JsonElement e : routes) {
                JsonObject r = e.getAsJsonObject();
                if (r.has("feasible") && !r.get("feasible").getAsBoolean()) continue;
                String mode = r.get("mode").getAsString();
                StringBuilder p = new StringBuilder(String.format(Locale.ROOT, "%s %d min, %.1f km", mode,
                        Math.round(r.get("travelTimeSeconds").getAsDouble() / 60.0), r.get("distanceMeters").getAsDouble() / 1000.0));
                if ("pt".equals(mode) && r.has("transfers")) p.append(", ").append(r.get("transfers").getAsInt()).append(" transfers");
                if (requires.containsKey(mode)) p.append(" (requires ").append(requires.get(mode)).append(")");
                parts.add(p.toString());
            }
            return String.join("; ", parts);
        } catch (Exception ignored) { }
        return compareJson;
    }

    private static String routesLine(String compareJson, Map<String, String> requires) {
        try {
            JsonObject o = JsonParser.parseString(compareJson).getAsJsonObject();
            JsonArray routes = o.getAsJsonArray("routes");
            if (routes == null) return compareJson;
            List<String> parts = new ArrayList<>();
            for (JsonElement e : routes) {
                JsonObject r = e.getAsJsonObject();
                String mode = r.has("mode") ? r.get("mode").getAsString() : null;
                if (mode != null && requires.containsKey(mode)) r.addProperty("requires", requires.get(mode));
                parts.add(r.toString());
            }
            return String.join(" ", parts);
        } catch (Exception ignored) { }
        return compareJson;
    }
}
