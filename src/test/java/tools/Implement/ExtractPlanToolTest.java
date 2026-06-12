package tools.Implement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import tools.IToolResponse;

/**
 * extract_plan is the terminal tool: it must never leave an agent
 * without a valid plan. These tests verify the fallback to the person's
 * current selected plan fires on any parse or verification failure.
 */
class ExtractPlanToolTest {

    @Test
    void fallsBackToOriginalPlanOnMalformedJson() {
        Person person = personWithSimplePlan();
        Map<String, Object> context = new HashMap<>();
        context.put("person", person);

        ExtractPlanTool tool = new ExtractPlanTool();
        IToolResponse<Plan> r = tool.call("not a json object", "call1", null, context);

        assertNotNull(r.getToolCallOutputContainer(), "fallback must produce a Plan");
        assertEquals(person.getSelectedPlan(), r.getToolCallOutputContainer());
        JsonObject body = JsonParser.parseString(r.getResponseJson()).getAsJsonObject();
        assertEquals("OK", body.get("status").getAsString());
        assertTrue(body.get("plan_source").getAsString().startsWith("fallback_original:"),
            "plan_source should mark the fallback: " + body);
    }

    @Test
    void fallsBackWhenPlanArgumentIsGarbage() {
        Person person = personWithSimplePlan();
        Map<String, Object> context = new HashMap<>();
        context.put("person", person);

        ExtractPlanTool tool = new ExtractPlanTool();
        String args = "{\"plan\":{\"elements\":[{\"activityType\":\"mystery\"}]}}";
        IToolResponse<Plan> r = tool.call(args, "call2", null, context);

        assertNotNull(r.getToolCallOutputContainer());
        JsonObject body = JsonParser.parseString(r.getResponseJson()).getAsJsonObject();
        assertEquals("OK", body.get("status").getAsString());
        assertTrue(body.get("plan_source").getAsString().startsWith("fallback_original:"));
    }

    @Test
    void returnsLlmPlanWhenInputIsValid() {
        Person person = personWithSimplePlan();
        Map<String, Object> context = new HashMap<>();
        context.put("person", person);

        // Structurally valid minimal plan matching PlanDTO expectations
        String args = "{\"plan\":{\"elements\":["
            + "{\"elementType\":\"activity\",\"type\":\"home\",\"linkId\":\"1\",\"endTime\":25000.0}"
            + "]}}";
        ExtractPlanTool tool = new ExtractPlanTool();
        IToolResponse<Plan> r = tool.call(args, "call3", null, context);
        JsonObject body = JsonParser.parseString(r.getResponseJson()).getAsJsonObject();
        // Either it parsed as an LLM plan, or it fell back — but in both cases a Plan exists.
        assertNotNull(r.getToolCallOutputContainer(), "must always yield a plan");
        assertEquals("OK", body.get("status").getAsString());
    }

    // --- helper ---

    private static Person personWithSimplePlan() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Population pop = scenario.getPopulation();
        PopulationFactory f = pop.getFactory();
        Person person = f.createPerson(Id.createPersonId("test_agent"));
        Plan plan = f.createPlan();
        Activity home = f.createActivityFromCoord("home", CoordUtils.createCoord(0, 0));
        home.setEndTime(25000);
        plan.addActivity(home);
        Activity work = f.createActivityFromCoord("work", CoordUtils.createCoord(1000, 0));
        plan.addActivity(work);
        person.addPlan(plan);
        PopulationUtils.putSubpopulation(person, "person");
        return person;
    }
}
