package tools.Implement;

import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.Facility;
import rag.IVectorDB;
import tools.DefaultToolResponse;
import tools.ErrorMessages;
import tools.IToolResponse;
import tools.ITool;
import tools.ToolArgument;
import tools.ToolArgumentDTO;
import tools.VerificationFailedException;

import jakarta.inject.Provider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Terminal ("dummy") tool for decision-level replanning: the LLM returns one
 * entry per trip it wants to change (mode and/or departure shift) and MATSim's
 * TripRouter builds the legs. The plan is therefore valid by construction —
 * no hand-written routes, no invented links, no frozen stage activities — and
 * the model generates a few dozen tokens instead of a full plan JSON.
 *
 * <p>Output container is the new {@link Plan}, so the strategy module treats
 * it exactly like {@code extract_plan}.
 */
public class DecideTripsTool implements ITool<Plan> {

    private static final Logger log = LogManager.getLogger(DecideTripsTool.class);

    public static final String Name = "decide_trips";
    private static final double DEFAULT_DEPARTURE = 8 * 3600.0;

    /** Routing seam so the plan surgery is testable without a MATSim injector. */
    public interface Router {
        List<? extends PlanElement> route(String mode, Facility from, Facility to, double departureTime, Person person);
    }

    private final Map<String, ToolArgument<?, ? extends ToolArgumentDTO<?>>> arguments = new HashMap<>();

    public DecideTripsTool() {
        registerArgument(TripDecisionsDTO.forArgument("decisions"));
    }

    @Override public String getName() { return Name; }
    @Override public Class<Plan> getOutputClass() { return Plan.class; }
    @Override public boolean isDummy() { return true; }
    @Override public Map<String, ToolArgument<?, ? extends ToolArgumentDTO<?>>> getRegisteredArguments() { return arguments; }

    @Override
    public String getDescription() {
        return "Final step: commit your day. Give one entry per trip you change (trip number, mode, "
             + "optional departure shift in minutes). Trips you do not list stay exactly as planned; "
             + "an empty list keeps the whole day. Routes are computed for you.";
    }

    @Override
    public void verifyArguments(Map<String, Object> args, Map<String, Object> context, ErrorMessages em)
            throws VerificationFailedException {
        @SuppressWarnings("unchecked")
        List<TripDecision> decisions = (List<TripDecision>) args.get("decisions");
        if (decisions == null) throw new VerificationFailedException(List.of("decide_trips needs a 'decisions' list (may be empty)."));
        Person person = personFromContext(context);
        Plan plan = person.getSelectedPlan();
        List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(plan);
        Map<Integer, TripDecision> decided = new HashMap<>();
        for (TripDecision d : decisions) {
            if (d.trip() < 1 || d.trip() > trips.size()) {
                em.addErrorMessages("trip " + d.trip() + " does not exist; the day has " + trips.size() + " trips");
                continue;
            }
            if (d.mode() == null || !TripDecisionsDTO.MODES.contains(d.mode())) {
                em.addErrorMessages("trip " + d.trip() + ": unknown mode '" + d.mode() + "'");
                continue;
            }
            decided.put(d.trip(), d);
        }
        for (String vehicle : List.of("car", "bike")) {
            checkVehicleChain(vehicle, person, plan, trips, decided, em);
        }
        if (!em.isEmpty()) throw new VerificationFailedException(em.getErrorMessages());
    }

    /**
     * Follows the day trip by trip with the decided modes applied over the
     * current ones, moving the vehicle along whenever a trip uses it, and
     * reports any decided trip that would use the vehicle from a place it has
     * not been brought to. Undecided trips are the plan as simulated, so they
     * are never reported, but they still move the vehicle.
     */
    private static void checkVehicleChain(String vehicle, Person person, Plan plan, List<TripStructureUtils.Trip> trips,
                                          Map<Integer, TripDecision> decided, ErrorMessages em) {
        AvailableModesTool.VehicleAccess access = AvailableModesTool.vehicleAccess(person, plan, vehicle);
        String location = access.startLocation();
        for (int i = 0; i < trips.size(); i++) {
            TripStructureUtils.Trip trip = trips.get(i);
            TripDecision d = decided.get(i + 1);
            String mode = d != null ? d.mode() : mainMode(trip);
            if (!vehicle.equals(mode)) continue;
            String origin = facilityOf(trip.getOriginActivity());
            if (d != null) {
                if (!access.owned()) {
                    em.addErrorMessages("trip " + d.trip() + ": you have no " + vehicle);
                } else if (origin != null && location != null
                        && !AvailableModesTool.normalizeFacility(origin).equals(AvailableModesTool.normalizeFacility(location))) {
                    em.addErrorMessages("trip " + d.trip() + ": your " + vehicle + " is not at " + origin + " (it is at " + location
                            + "); use it on the earlier trips too so it is with you, or pick another mode");
                }
            }
            String destination = facilityOf(trip.getDestinationActivity());
            if (destination != null) location = destination;
        }
    }

    /** Routing mode of the trip's first leg, else its first non-walk leg, else its first leg. */
    private static String mainMode(TripStructureUtils.Trip trip) {
        List<Leg> legs = trip.getLegsOnly();
        if (legs.isEmpty()) return null;
        if (legs.get(0).getRoutingMode() != null) return legs.get(0).getRoutingMode();
        for (Leg l : legs) if (!"walk".equals(l.getMode())) return l.getMode();
        return legs.get(0).getMode();
    }

    private static String facilityOf(Activity act) {
        return act != null && act.getFacilityId() != null ? act.getFacilityId().toString() : null;
    }

    @Override
    public IToolResponse<Plan> callTool(String id, Map<String, Object> args, IVectorDB vectorDB, Map<String, Object> context) {
        @SuppressWarnings("unchecked")
        List<TripDecision> decisions = (List<TripDecision>) args.get("decisions");
        Person person = personFromContext(context);
        ActivityFacilities facilities = (ActivityFacilities) context.get("activityFacilities");
        @SuppressWarnings("unchecked")
        Provider<TripRouter> routerProvider = (Provider<TripRouter>) context.get("tripRoutersProvider");
        if (facilities == null || routerProvider == null) {
            throw new RuntimeException("decide_trips needs 'activityFacilities' and 'tripRoutersProvider' in the context");
        }
        Router router = (mode, from, to, dep, p) -> routerProvider.get().calcRoute(mode, from, to, dep, p, null);
        Plan out = apply(person.getSelectedPlan(), person, decisions, router, facilities);
        JsonObject response = new JsonObject();
        response.addProperty("status", "OK");
        response.addProperty("changedTrips", decisions.size());
        return new DefaultToolResponse<>(id, getName(), response.toString(), out, true);
    }

    /**
     * Copies {@code original} and rewrites the decided trips: shifts the origin
     * activity's end time, then replaces the trip's elements by a freshly routed
     * trip of the chosen mode. Undecided trips are untouched.
     */
    public static Plan apply(Plan original, Person person, List<TripDecision> decisions, Router router,
                             ActivityFacilities facilities) {
        Plan out = PopulationUtils.createPlan(person);
        PopulationUtils.copyFromTo(original, out);
        // Decisions are applied from the last trip backwards so trip indices
        // (taken from the original plan) stay valid while elements are replaced.
        List<TripDecision> ordered = decisions.stream()
                .sorted((a, b) -> Integer.compare(b.trip(), a.trip())).toList();
        for (TripDecision d : ordered) {
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(out);
            if (d.trip() < 1 || d.trip() > trips.size()) continue;
            TripStructureUtils.Trip trip = trips.get(d.trip() - 1);
            Activity origin = trip.getOriginActivity();
            Activity destination = trip.getDestinationActivity();
            double departure = departureOf(trip);
            int shift = d.shiftMinutesOrZero();
            if (shift != 0 && origin.getEndTime().isDefined()) {
                departure = Math.max(0, origin.getEndTime().seconds() + shift * 60.0);
                origin.setEndTime(departure);
            }
            String mode = d.mode() != null ? d.mode() : trip.getLegsOnly().get(0).getMode();
            Facility from = FacilitiesUtils.toFacility(origin, facilities);
            Facility to = FacilitiesUtils.toFacility(destination, facilities);
            List<? extends PlanElement> routed = router.route(mode, from, to, departure, person);
            if (routed == null || routed.isEmpty()) {
                log.warn("No {} route for trip {} of person {}; trip left unchanged", mode, d.trip(), person.getId());
                continue;
            }
            TripRouter.insertTrip(out, origin, routed, destination);
        }
        return out;
    }

    private static double departureOf(TripStructureUtils.Trip trip) {
        Activity origin = trip.getOriginActivity();
        if (origin.getEndTime().isDefined()) return origin.getEndTime().seconds();
        for (var leg : trip.getLegsOnly()) {
            if (leg.getDepartureTime().isDefined()) return leg.getDepartureTime().seconds();
        }
        return DEFAULT_DEPARTURE;
    }

    private static Person personFromContext(Map<String, Object> context) {
        Object p = context.get("person");
        if (!(p instanceof Person person)) throw new RuntimeException("decide_trips needs 'person' in the context");
        return person;
    }
}
