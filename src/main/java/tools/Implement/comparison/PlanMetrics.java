package tools.Implement.comparison;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Observable plan-level metrics returned by {@code evaluate_plan}. Surfaces
 * totals + mode mix + structural warnings, but no score or utility — the LLM
 * must decide whether the trade-offs are acceptable.
 */
public record PlanMetrics(
        double totalTravelTimeSeconds,
        double totalDistanceMeters,
        int legCount,
        int activityCount,
        Map<String, Double> modeDurationSeconds,
        List<String> warnings) {

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("totalTravelTimeSeconds", totalTravelTimeSeconds);
        o.addProperty("totalDistanceMeters", totalDistanceMeters);
        o.addProperty("legCount", legCount);
        o.addProperty("activityCount", activityCount);

        JsonObject modes = new JsonObject();
        modeDurationSeconds.forEach(modes::addProperty);
        o.add("modeDurationSeconds", modes);

        JsonArray w = new JsonArray();
        warnings.forEach(w::add);
        o.add("warnings", w);

        return o;
    }
}
