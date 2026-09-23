package tools.Implement;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import tools.ErrorMessages;
import tools.VerificationFailedException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DecideTripsToolTest {

    private static ActivityFacilities facilities;

    private static Person personHomeWorkHome() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        facilities = scenario.getActivityFacilities();
        var ff = facilities.getFactory();
        ActivityFacility home = ff.createActivityFacility(Id.create("home_f", ActivityFacility.class), new Coord(0, 0));
        ActivityFacility work = ff.createActivityFacility(Id.create("work_f", ActivityFacility.class), new Coord(1000, 0));
        facilities.addActivityFacility(home);
        facilities.addActivityFacility(work);
        Person p = PopulationUtils.getFactory().createPerson(Id.createPersonId("p"));
        p.getAttributes().putAttribute("carAvail", "always");
        Plan plan = PopulationUtils.createPlan(p);
        Activity h1 = PopulationUtils.createActivityFromFacilityId("home", home.getId()); h1.setEndTime(7 * 3600); plan.addActivity(h1);
        plan.addLeg(PopulationUtils.createLeg("car"));
        Activity w = PopulationUtils.createActivityFromFacilityId("work", work.getId()); w.setEndTime(16 * 3600); plan.addActivity(w);
        // pt trip home: walk > pt interaction > pt > pt interaction > walk
        Leg l1 = PopulationUtils.createLeg("walk"); l1.setRoutingMode("pt"); plan.addLeg(l1);
        plan.addActivity(PopulationUtils.createActivityFromCoord("pt interaction", new Coord(900, 0)));
        Leg l2 = PopulationUtils.createLeg("pt"); l2.setRoutingMode("pt"); plan.addLeg(l2);
        plan.addActivity(PopulationUtils.createActivityFromCoord("pt interaction", new Coord(100, 0)));
        Leg l3 = PopulationUtils.createLeg("walk"); l3.setRoutingMode("pt"); plan.addLeg(l3);
        Activity h2 = PopulationUtils.createActivityFromFacilityId("home", home.getId()); plan.addActivity(h2);
        p.addPlan(plan); p.setSelectedPlan(plan);
        return p;
    }

    /** Fake router: one leg of the requested mode, departure time recorded. */
    private static final DecideTripsTool.Router FAKE = (mode, from, to, dep, person) -> {
        Leg leg = PopulationUtils.createLeg(mode);
        leg.setRoutingMode(mode);
        leg.setDepartureTime(dep);
        return List.of(leg);
    };

    @Test
    void replacesDecidedTripAndKeepsTheRest() {
        Person p = personHomeWorkHome();
        Plan out = DecideTripsTool.apply(p.getSelectedPlan(), p, List.of(new TripDecision(2, "walk", null)), FAKE, facilities);
        List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(out);
        assertEquals(2, trips.size());
        assertEquals("car", trips.get(0).getLegsOnly().get(0).getMode(), "undecided trip untouched");
        assertEquals(1, trips.get(1).getTripElements().size(), "pt chain replaced by one routed leg");
        assertEquals("walk", trips.get(1).getLegsOnly().get(0).getMode());
        assertEquals(16 * 3600, trips.get(1).getLegsOnly().get(0).getDepartureTime().seconds(), 1e-9);
        assertEquals(3, out.getPlanElements().stream().filter(e -> e instanceof Activity).count(), "home, work, home remain");
        assertEquals(9, p.getSelectedPlan().getPlanElements().size(), "original plan not modified");
    }

    @Test
    void departureShiftMovesOriginEndTime() {
        Person p = personHomeWorkHome();
        Plan out = DecideTripsTool.apply(p.getSelectedPlan(), p, List.of(new TripDecision(1, "car", -30)), FAKE, facilities);
        Activity home = (Activity) out.getPlanElements().get(0);
        assertEquals(6.5 * 3600, home.getEndTime().seconds(), 1e-9);
        assertEquals(6.5 * 3600, ((Leg) out.getPlanElements().get(1)).getDepartureTime().seconds(), 1e-9);
    }

    @Test
    void emptyDecisionListKeepsTheDay() {
        Person p = personHomeWorkHome();
        Plan out = DecideTripsTool.apply(p.getSelectedPlan(), p, List.of(), FAKE, facilities);
        assertEquals(p.getSelectedPlan().getPlanElements().size(), out.getPlanElements().size());
    }

    @Test
    void verificationRejectsMissingTripAndCarAwayFromPerson() {
        Person p = personHomeWorkHome();
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("person", p);
        DecideTripsTool tool = new DecideTripsTool();
        Map<String, Object> bad = Map.of("decisions", List.of(new TripDecision(5, "walk", null)));
        assertThrows(VerificationFailedException.class, () -> tool.verifyArguments(bad, ctx, new ErrorMessages()));
        // Drove to work (car at work) then pt home: choosing car for trip 2 is fine, car is at work.
        Map<String, Object> ok = Map.of("decisions", List.of(new TripDecision(2, "car", null)));
        assertDoesNotThrow(() -> tool.verifyArguments(ok, ctx, new ErrorMessages()));
    }

    /** Same day as {@link #personHomeWorkHome()} but both trips by pt: the car stays at home. */
    private static Person personHomeWorkHomeByPt() {
        Person p = personHomeWorkHome();
        Plan plan = p.getSelectedPlan();
        Leg first = (Leg) plan.getPlanElements().get(1);
        first.setMode("pt"); first.setRoutingMode("pt");
        return p;
    }

    @Test
    void verificationFollowsTheVehicleAlongTheDecidedChain() {
        Person p = personHomeWorkHomeByPt();
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("person", p);
        DecideTripsTool tool = new DecideTripsTool();
        // Drive out and drive back: the car is brought to work by trip 1, so trip 2 can use it.
        Map<String, Object> both = Map.of("decisions", List.of(new TripDecision(1, "car", null), new TripDecision(2, "car", null)));
        assertDoesNotThrow(() -> tool.verifyArguments(both, ctx, new ErrorMessages()));
        // Driving back alone: the car is still at home.
        Map<String, Object> backOnly = Map.of("decisions", List.of(new TripDecision(2, "car", null)));
        VerificationFailedException ex = assertThrows(VerificationFailedException.class,
                () -> tool.verifyArguments(backOnly, ctx, new ErrorMessages()));
        assertTrue(ex.getMessage().contains("car is not at work_f"), ex.getMessage());
        // Switching trip 1 away from the car breaks a chain the current plan relied on.
        Person drove = personHomeWorkHome();
        ctx.put("person", drove);
        Map<String, Object> broken = Map.of("decisions", List.of(new TripDecision(1, "pt", null), new TripDecision(2, "car", null)));
        assertThrows(VerificationFailedException.class, () -> tool.verifyArguments(broken, ctx, new ErrorMessages()));
        // No car at all.
        drove.getAttributes().putAttribute("carAvail", "never");
        Map<String, Object> noCar = Map.of("decisions", List.of(new TripDecision(1, "car", null)));
        assertThrows(VerificationFailedException.class, () -> tool.verifyArguments(noCar, ctx, new ErrorMessages()));
    }

    @Test
    void dtoAcceptsArrayWrappedNestedAndStringified() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        String arr = "[{\"trip\":2,\"mode\":\"car\",\"departureShiftMinutes\":-15}]";
        for (String json : List.of(
                "{\"value\":" + arr + "}",
                "{\"decisions\":" + arr + "}",
                "{\"value\":\"" + arr.replace("\"", "\\\"") + "\"}",
                "{\"decisions\":\"" + arr.replace("\"", "\\\"") + "\"}")) {
            TripDecisionsDTO dto = TripDecisionsDTO.fromJsonObject(
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject(), gson);
            List<TripDecision> base = dto.toBaseClass(Map.of(), new ErrorMessages());
            assertEquals(1, base.size(), json);
            assertEquals(2, base.get(0).trip());
            assertEquals(-15, base.get(0).departureShiftMinutes());
        }
        TripDecisionsDTO empty = TripDecisionsDTO.fromJsonObject(
                com.google.gson.JsonParser.parseString("{\"value\":\"[]\"}").getAsJsonObject(), gson);
        assertTrue(empty.toBaseClass(Map.of(), new ErrorMessages()).isEmpty());
    }

    @Test
    void toolCallParsesStringifiedDecisionsEndToEnd() {
        Person p = personHomeWorkHome();
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("person", p);
        ctx.put("activityFacilities", facilities);
        ctx.put("tripRoutersProvider", (jakarta.inject.Provider<org.matsim.core.router.TripRouter>) () -> {
            throw new UnsupportedOperationException("router not needed: verification happens first");
        });
        DecideTripsTool tool = new DecideTripsTool();
        // Unknown trip 9 must be reported through verification, proving the
        // stringified list was parsed rather than dropped.
        var resp = tool.call("{\"decisions\":\"[{\\\"trip\\\":9,\\\"mode\\\":\\\"walk\\\"}]\"}", "c1", null, ctx);
        assertNull(resp.getToolCallOutputContainer());
        assertTrue(resp.getResponseJson().contains("trip 9"), resp.getResponseJson());
    }

    @Test
    void dtoParsesAndValidatesModes() {
        TripDecisionsDTO dto = new com.google.gson.Gson().fromJson(
                "{\"decisions\":[{\"trip\":1,\"mode\":\"PT\"},{\"trip\":2,\"mode\":\"teleport\"}]}", TripDecisionsDTO.class);
        ErrorMessages em = new ErrorMessages();
        assertFalse(dto.isVerified(em));
        List<TripDecision> base = dto.toBaseClass(Map.of(), new ErrorMessages());
        assertEquals("pt", base.get(0).mode());
    }
}
