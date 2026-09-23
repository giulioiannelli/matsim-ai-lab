package matsimBinding.oneshot;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacility;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneShotContextBuilderTest {

    private static Plan planHomeWorkHomeByPt() {
        Population pop = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        Plan plan = PopulationUtils.createPlan();
        Activity home = PopulationUtils.createActivityFromCoord("home", null);
        home.setFacilityId(Id.create("f_home", ActivityFacility.class));
        home.setEndTime(7 * 3600);
        plan.addActivity(home);
        Leg walk = PopulationUtils.createLeg("walk"); walk.setRoutingMode("pt"); plan.addLeg(walk);
        Activity stage = PopulationUtils.createActivityFromCoord("pt interaction", null); plan.addActivity(stage);
        Leg pt = PopulationUtils.createLeg("pt"); pt.setRoutingMode("pt"); plan.addLeg(pt);
        Activity work = PopulationUtils.createActivityFromCoord("work", null);
        work.setFacilityId(Id.create("f_work", ActivityFacility.class));
        work.setMaximumDuration(8 * 3600);
        plan.addActivity(work);
        Leg car = PopulationUtils.createLeg("car"); plan.addLeg(car);
        Activity home2 = PopulationUtils.createActivityFromCoord("home", null);
        home2.setFacilityId(Id.create("f_home", ActivityFacility.class));
        plan.addActivity(home2);
        return plan;
    }

    @Test
    void enumeratesTripsBetweenRealActivitiesWithModesAndTimes() {
        List<OneShotContextBuilder.Trip> trips = OneShotContextBuilder.enumerateTrips(planHomeWorkHomeByPt());
        assertEquals(2, trips.size());
        assertEquals("f_home", trips.get(0).fromFacilityId());
        assertEquals("f_work", trips.get(0).toFacilityId());
        assertEquals(7 * 3600, trips.get(0).departureTime(), 1e-9);
        assertEquals("pt", trips.get(0).currentMode(), "stage legs collapse to the routing mode");
        assertEquals("car", trips.get(1).currentMode());
        assertEquals(15 * 3600, trips.get(1).departureTime(), 1e-9, "end time + maximum duration");
    }

    private static OneShotContextBuilder.Trip trip(String from, String to) {
        return new OneShotContextBuilder.Trip(from, to, 8 * 3600, "pt");
    }

    @Test
    void vehicleIsUsableAlongAnUnbrokenChainFromWhereItIsParked() {
        List<OneShotContextBuilder.Trip> tour = List.of(trip("home", "work"), trip("work", "home"));
        String[] req = OneShotContextBuilder.vehicleRequirements(tour, "home");
        assertEquals("", req[0], "parked at the origin: no condition");
        assertEquals("trip 1 as well", req[1], "back from work only if driven there");

        List<OneShotContextBuilder.Trip> longer = List.of(trip("home", "work"), trip("work", "shop"),
                trip("shop", "home"), trip("home", "gym"), trip("gym", "home"));
        req = OneShotContextBuilder.vehicleRequirements(longer, "home");
        assertEquals("", req[0]);
        assertEquals("trip 1 as well", req[1]);
        assertEquals("trips 1-2 as well", req[2]);
        assertEquals("", req[3], "chain restarts whenever a trip leaves from where the vehicle is parked");
        assertEquals("trip 4 as well", req[4]);

        req = OneShotContextBuilder.vehicleRequirements(tour, "elsewhere");
        assertEquals(null, req[0], "vehicle never reachable: not offered");
        assertEquals(null, req[1]);
    }

    @Test
    void parsesAvailableModes() {
        String json = "{\"availableModes\":[{\"mode\":\"walk\",\"available\":true},{\"mode\":\"car\",\"available\":false},{\"mode\":\"pt\",\"available\":true}]}";
        assertEquals(List.of("walk", "pt"), OneShotContextBuilder.availableModesFrom(json));
        assertTrue(OneShotContextBuilder.availableModesFrom("not json").isEmpty());
    }

    @Test
    void oneShotFilterAdvertisesActionToolsOnly() {
        assertEquals(java.util.Set.of("extract_plan", "router_tool"),
                new OneShotToolFilter().visibleTools(null));
    }
}
