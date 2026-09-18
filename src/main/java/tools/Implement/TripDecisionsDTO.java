package tools.Implement;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import tools.ErrorMessages;
import tools.ToolArgument;
import tools.ToolArgumentDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JSON shape of the {@code decisions} argument of {@link DecideTripsTool}. */
public class TripDecisionsDTO extends ToolArgumentDTO<List<TripDecision>> {

    public static final List<String> MODES = List.of("car", "pt", "walk", "bike", "car_passenger");

    public List<Entry> decisions = new ArrayList<>();

    public static class Entry {
        public Integer trip;
        public String mode;
        public Integer departureShiftMinutes;
    }

    public TripDecisionsDTO() {}

    public TripDecisionsDTO(List<TripDecision> base) {
        if (base == null) return;
        for (TripDecision d : base) {
            Entry e = new Entry();
            e.trip = d.trip(); e.mode = d.mode(); e.departureShiftMinutes = d.departureShiftMinutes();
            decisions.add(e);
        }
    }

    @Override
    public List<TripDecision> toBaseClass(Map<String, Object> context, ErrorMessages em) {
        List<TripDecision> out = new ArrayList<>();
        if (decisions == null) return out;
        for (Entry e : decisions) {
            if (e == null || e.trip == null) { em.addErrorMessages("decision without a trip number"); continue; }
            out.add(new TripDecision(e.trip, e.mode == null ? null : e.mode.trim().toLowerCase(), e.departureShiftMinutes));
        }
        return out;
    }

    @Override
    public boolean isVerified(ErrorMessages em) {
        boolean ok = true;
        if (decisions == null) return true;
        for (Entry e : decisions) {
            if (e == null) continue;
            if (e.trip == null || e.trip < 1) { em.addErrorMessages("trip must be a positive trip number"); ok = false; }
            if (e.mode != null && !MODES.contains(e.mode.trim().toLowerCase())) {
                em.addErrorMessages("unknown mode '" + e.mode + "', use one of " + MODES); ok = false;
            }
        }
        return ok;
    }

    public static final JsonObject STATIC_SCHEMA;
    static {
        JsonObject trip = new JsonObject();
        trip.addProperty("type", "integer");
        trip.addProperty("description", "Trip number as listed in your day (1 = first trip).");
        JsonObject mode = new JsonObject();
        mode.addProperty("type", "string");
        JsonArray modes = new JsonArray();
        for (String m : MODES) modes.add(m);
        mode.add("enum", modes);
        mode.addProperty("description", "Mode for this trip. Repeat the current mode if you only shift the departure.");
        JsonObject shift = new JsonObject();
        shift.addProperty("type", "integer");
        shift.addProperty("description", "Optional. Leave this many minutes later (positive) or earlier (negative) than planned.");
        JsonObject itemProps = new JsonObject();
        itemProps.add("trip", trip);
        itemProps.add("mode", mode);
        itemProps.add("departureShiftMinutes", shift);
        JsonArray itemRequired = new JsonArray();
        itemRequired.add("trip");
        itemRequired.add("mode");
        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        item.add("properties", itemProps);
        item.add("required", itemRequired);
        JsonObject list = new JsonObject();
        list.addProperty("type", "array");
        list.add("items", item);
        list.addProperty("description", "One entry per trip you change. Trips you leave out stay as they are; an empty list keeps the whole day.");
        JsonObject props = new JsonObject();
        props.add("decisions", list);
        JsonArray required = new JsonArray();
        required.add("decisions");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", required);
        STATIC_SCHEMA = schema;
    }

    public static ToolArgument<List<TripDecision>, TripDecisionsDTO> forArgument(String name) {
        return new ToolArgument<>(name, TripDecisionsDTO.class, TripDecisionsDTO::new, STATIC_SCHEMA);
    }
}
