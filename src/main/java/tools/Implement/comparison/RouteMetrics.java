package tools.Implement.comparison;

import com.google.gson.JsonObject;

/**
 * Observable metrics for one routed trip returned by {@code compare_routes}.
 * Intentionally narrow: travel time, distance, transfer count, feasibility.
 * No scoring, no utility — those judgments belong to the LLM.
 */
public record RouteMetrics(
        String mode,
        double travelTimeSeconds,
        double distanceMeters,
        int transfers,
        boolean feasible,
        String reason) {

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode);
        o.addProperty("travelTimeSeconds", travelTimeSeconds);
        o.addProperty("distanceMeters", distanceMeters);
        o.addProperty("transfers", transfers);
        o.addProperty("feasible", feasible);
        if (reason != null) o.addProperty("reason", reason);
        return o;
    }

    public static RouteMetrics infeasible(String mode, String reason) {
        return new RouteMetrics(mode, 0.0, 0.0, 0, false, reason);
    }
}
