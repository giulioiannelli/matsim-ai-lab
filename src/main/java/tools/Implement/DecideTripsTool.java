package tools.Implement;

import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Activity;
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
        Map<String, Object> attrs = person.getAttributes().getAsMap();
        boolean carOwned = attrs.get("carAvail") == null || !"never".equalsIgnoreCase(attrs.get("carAvail").toString());
        boolean bikeOwned = attrs.get("bikeAvailability") != null && !"never".equalsIgnoreCase(attrs.get("bikeAvailability").toString());
        for (TripDecision d : decisions) {
            if (d.trip() < 1 || d.trip() > trips.size()) {
                em.addErrorMessages("trip " + d.trip() + " does not exist; the day has " + trips.size() + " trips");
                continue;
            }
            if (d.mode() == null || !TripDecisionsDTO.MODES.contains(d.mode())) {
                em.addErrorMessages("trip " + d.trip() + ": unknown mode '" + d.mode() + "'");
                continue;
            }
            Activity origin = trips.get(d.trip() - 1).getOriginActivity();
            String here = origin.getFacilityId() != null ? origin.getFacilityId().toString() : null;
            if ("car".equals(d.mode())) {
                if (!carOwned) em.addErrorMessages("trip " + d.trip() + ": you have no car");
                else if (here != null && !here.equals(AvailableModesTool.findVehicleLocation(plan, "car", here)))
                    em.addErrorMessages("trip " + d.trip() + ": your car is not at " + here + " (it is at "
                            + AvailableModesTool.findVehicleLocation(plan, "car", here) + ")");
            }
            if ("bike".equals(d.mode())) {
                if (!bikeOwned) em.addErrorMessages("trip " + d.trip() + ": you have no bike");
                else if (here != null && !here.equals(AvailableModesTool.findVehicleLocation(plan, "bike", here)))
                    em.addErrorMessages("trip " + d.trip() + ": your bike is not at " + here);
            }
        }
        if (!em.isEmpty()) throw new VerificationFailedException(em.getErrorMessages());
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
