package tools.Implement.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.facilities.ActivityFacility;

import tools.ErrorMessages;
import tools.VerificationFailedException;

class EvaluatePlanToolTest {

    @Test
    void computesTotalsAndModeShareForSimpleDay() {
        Plan plan = PopulationUtils.createPlan();
        addActivity(plan, "home", "f1", 25000);
        addLeg(plan, "car", 600.0, 4500.0);
        addActivity(plan, "work", "f2", 50000);
        addLeg(plan, "car", 600.0, 4500.0);
        addActivity(plan, "home", "f1", Double.NaN);

        PlanMetrics m = EvaluatePlanTool.compute(plan);

        assertEquals(3, m.activityCount());
        assertEquals(2, m.legCount());
        assertEquals(1200.0, m.totalTravelTimeSeconds(), 0.001);
        assertEquals(9000.0, m.totalDistanceMeters(), 0.001);
        assertTrue(m.modeDurationSeconds().containsKey("car"));
        assertTrue(m.warnings().isEmpty(),
                "round-trip car day should have no warnings: " + m.warnings());
    }

    @Test
    void flagsCarLegWhenCarIsNotAtOrigin() {
        // Go to work by pt, then try to drive home — car was never at work.
        Plan plan = PopulationUtils.createPlan();
        addActivity(plan, "home", "f1", 25000);
        addLeg(plan, "pt", 900.0, 5000.0);
        addActivity(plan, "work", "f2", 50000);
        addLeg(plan, "car", 600.0, 4500.0);
        addActivity(plan, "home", "f1", Double.NaN);

        PlanMetrics m = EvaluatePlanTool.compute(plan);

        assertFalse(m.warnings().isEmpty(), "car-chain violation should warn");
        assertTrue(m.warnings().stream().anyMatch(w -> w.contains("Car leg from f2")),
                "warning should cite the offending origin: " + m.warnings());
    }

    @Test
    void flagsActivityEndBeforePrevious() {
        Plan plan = PopulationUtils.createPlan();
        addActivity(plan, "home", "f1", 25000);
        addLeg(plan, "walk", 200.0, 300.0);
        addActivity(plan, "shop", "f2", 20000); // earlier than previous 25000
        PlanMetrics m = EvaluatePlanTool.compute(plan);
        assertTrue(m.warnings().stream().anyMatch(w -> w.contains("ends before preceding")),
                "timing violation should warn: " + m.warnings());
    }

    @Test
    void verifyArgumentsRejectsMissingPlan() {
        EvaluatePlanTool tool = new EvaluatePlanTool();
        ErrorMessages em = new ErrorMessages();
        assertThrows(VerificationFailedException.class,
                () -> tool.verifyArguments(new HashMap<>(), new HashMap<>(), em));
    }

    // --- helpers ---

    private static void addActivity(Plan plan, String type, String facilityId, double endTime) {
        Activity act = PopulationUtils.createActivityFromCoord(type, CoordUtils.createCoord(0, 0));
        act.setFacilityId(Id.create(facilityId, ActivityFacility.class));
        if (!Double.isNaN(endTime)) act.setEndTime(endTime);
        plan.addActivity(act);
    }

    private static void addLeg(Plan plan, String mode, double travelTime, double distance) {
        Leg leg = PopulationUtils.createLeg(mode);
        leg.setRoute(RouteUtils.createGenericRouteImpl(
                Id.create("l1", Link.class), Id.create("l2", Link.class)));
        leg.getRoute().setDistance(distance);
        leg.getRoute().setTravelTime(travelTime);
        leg.setTravelTime(travelTime);
        leg.setRoutingMode(mode);
        plan.addLeg(leg);
    }
}
