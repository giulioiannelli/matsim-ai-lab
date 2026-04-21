package tools.Implement.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;

import tools.ErrorMessages;
import tools.VerificationFailedException;

class CompareRoutesToolTest {

    @Test
    void parseModesSplitsAndNormalizesCsv() {
        List<String> modes = CompareRoutesTool.parseModes(" Car, PT,bike ,bike, , walk");
        assertEquals(List.of("car", "pt", "bike", "walk"), modes);
    }

    @Test
    void parseModesHandlesNull() {
        assertTrue(CompareRoutesTool.parseModes(null).isEmpty());
    }

    @Test
    void summariseAggregatesTravelTimeDistanceAndTransfers() {
        List<PlanElement> routed = new ArrayList<>();
        routed.add(leg("pt", 600.0, 5000.0));
        routed.add(leg("pt", 300.0, 2000.0));
        routed.add(leg("pt", 120.0, 500.0));

        RouteMetrics rm = CompareRoutesTool.summarise("pt", routed);

        assertTrue(rm.feasible());
        assertEquals("pt", rm.mode());
        assertEquals(1020.0, rm.travelTimeSeconds(), 0.001);
        assertEquals(7500.0, rm.distanceMeters(), 0.001);
        // 3 pt legs → 2 transfers
        assertEquals(2, rm.transfers());
    }

    @Test
    void summariseForNonPtHasZeroTransfers() {
        List<PlanElement> routed = new ArrayList<>();
        routed.add(leg("car", 420.0, 3200.0));
        RouteMetrics rm = CompareRoutesTool.summarise("car", routed);
        assertEquals(0, rm.transfers());
        assertEquals(420.0, rm.travelTimeSeconds(), 0.001);
    }

    @Test
    void verifyArgumentsRejectsNullContext() {
        CompareRoutesTool tool = new CompareRoutesTool();
        Map<String, Object> args = baseArgs();
        ErrorMessages em = new ErrorMessages();
        VerificationFailedException ex = assertThrows(
                VerificationFailedException.class,
                () -> tool.verifyArguments(args, null, em));
        assertFalse(em.getErrorMessages().isEmpty(), "should record at least one error reason");
        assertTrue(String.join("|", ex.getErrors()).toLowerCase().contains("context"));
    }

    @Test
    void verifyArgumentsRejectsEmptyFromFacility() {
        CompareRoutesTool tool = new CompareRoutesTool();
        Map<String, Object> args = baseArgs();
        args.put("fromFacilityId", "");
        ErrorMessages em = new ErrorMessages();
        assertThrows(VerificationFailedException.class,
                () -> tool.verifyArguments(args, new HashMap<>(), em));
    }

    private static Map<String, Object> baseArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("fromFacilityId", "1");
        args.put("toFacilityId", "2");
        args.put("modes", "car,pt");
        args.put("departureTimeSeconds", 25000.0);
        return args;
    }

    private static Leg leg(String mode, double travelTime, double distance) {
        Leg leg = PopulationUtils.createLeg(mode);
        leg.setRoute(RouteUtils.createGenericRouteImpl(
                Id.create("l1", Link.class), Id.create("l2", Link.class)));
        leg.getRoute().setDistance(distance);
        leg.getRoute().setTravelTime(travelTime);
        leg.setTravelTime(travelTime);
        return leg;
    }
}
