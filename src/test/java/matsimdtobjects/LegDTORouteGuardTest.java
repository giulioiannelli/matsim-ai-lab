package matsimdtobjects;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.population.Leg;
import tools.ErrorMessages;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LegDTORouteGuardTest {

    private static Leg legFrom(String json) {
        LegDTO dto = LegDTO.fromJsonObject(JsonParser.parseString(json).getAsJsonObject(), new Gson());
        return dto.toBaseClass(Map.of(), new ErrorMessages());
    }

    @Test
    void routeStubWithoutLinksIsDroppedNotAttached() {
        Leg leg = legFrom("{\"elementType\":\"leg\",\"mode\":\"walk\",\"routingMode\":\"walk\","
                + "\"departureTimeSeconds\":28800.0,\"route\":{\"routeType\":\"generic\"}}");
        assertNotNull(leg);
        assertEquals("walk", leg.getMode());
        assertNull(leg.getRoute(), "a linkless route must not reach MATSim");
    }

    @Test
    void routeWithBothLinksIsKept() {
        Leg leg = legFrom("{\"elementType\":\"leg\",\"mode\":\"walk\",\"routingMode\":\"walk\","
                + "\"route\":{\"routeType\":\"generic\",\"startLinkId\":\"1\",\"endLinkId\":\"2\",\"distance\":100.0}}");
        assertNotNull(leg.getRoute());
        assertEquals("1", leg.getRoute().getStartLinkId().toString());
    }
}
