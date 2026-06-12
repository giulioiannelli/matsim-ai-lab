package tools.Implement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.population.PopulationUtils;
import org.matsim.facilities.ActivityFacility;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import tools.IToolResponse;

/**
 * Driving licences are not modelled in every scenario (Sioux Falls agents carry
 * {@code carAvail} + an assigned car vehicle but no {@code hasLicense}). A
 * missing attribute must not be read as "unlicensed", which previously denied
 * car to every car-owning agent and contradicted their own survey plans.
 */
class AvailableModesToolTest {

    private static final String HOME = "home_fac_1";

    private static Person personWith(Map<String, String> attrs) {
        Person p = PopulationUtils.getFactory().createPerson(Id.create("p1", Person.class));
        attrs.forEach((k, v) -> p.getAttributes().putAttribute(k, v));
        Plan plan = PopulationUtils.getFactory().createPlan();
        Activity home = PopulationUtils.createActivityFromFacilityId(
                "home", Id.create(HOME, ActivityFacility.class));
        plan.addActivity(home);
        p.addPlan(plan);
        p.setSelectedPlan(plan);
        return p;
    }

    private static JsonObject carMode(Person person) {
        Map<String, Object> args = new HashMap<>();
        args.put("fromFacilityId", HOME);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("person", person);
        IToolResponse<String> resp = new AvailableModesTool().callTool("id", args, null, ctx);
        JsonObject root = JsonParser.parseString(resp.getResponseJson()).getAsJsonObject();
        JsonArray modes = root.getAsJsonArray("availableModes");
        for (int i = 0; i < modes.size(); i++) {
            JsonObject m = modes.get(i).getAsJsonObject();
            if ("car".equals(m.get("mode").getAsString())) {
                return m;
            }
        }
        throw new AssertionError("no car mode in response: " + resp.getResponseJson());
    }

    @Test
    void carOwnerWithoutLicenceAttributeCanDrive() {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("carAvail", "always");   // no hasLicense at all (Sioux Falls)
        JsonObject car = carMode(personWith(attrs));
        assertTrue(car.get("available").getAsBoolean(),
                () -> "absent licence must not deny car: " + car);
        assertFalse(car.get("reason").getAsString().toLowerCase().contains("driving license"), car.toString());
    }

    @Test
    void explicitNoLicenceStillDeniesCar() {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("carAvail", "always");
        attrs.put("hasLicense", "false");
        JsonObject car = carMode(personWith(attrs));
        assertFalse(car.get("available").getAsBoolean(),
                () -> "explicit no-licence must deny car: " + car);
    }

    @Test
    void noCarAvailabilityDeniesCar() {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("carAvail", "never");
        JsonObject car = carMode(personWith(attrs));
        assertFalse(car.get("available").getAsBoolean(), car.toString());
    }
}
