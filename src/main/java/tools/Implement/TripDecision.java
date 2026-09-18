package tools.Implement;

/**
 * One trip-level decision returned by the LLM through {@link DecideTripsTool}:
 * which trip (1-based, in plan order), the mode to use, and an optional shift
 * of the departure (end of the preceding activity) in minutes.
 */
public record TripDecision(int trip, String mode, Integer departureShiftMinutes) {

    public int shiftMinutesOrZero() {
        return departureShiftMinutes == null ? 0 : departureShiftMinutes;
    }
}
