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
