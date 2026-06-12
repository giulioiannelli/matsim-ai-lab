package chatrequest.grammar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

/**
 * Offline gate: structural assertions on the grammar schema. No LLM calls.
 * Catches regressions in {@link GrammarSchema#build} that would burn GPU later.
 */
class GrammarSchemaTest {

    @Test
    void buildContainsOneOfBranchPerTool() {
        JsonObject schema = GrammarSchema.build(List.of(
            fnSchema("router_tool", scalarProp("fromFacilityId", "string")),
            fnSchema("extract_plan", planLikeProp("plan"))
        ));
        JsonArray oneOf = oneOfBranches(schema);
        assertEquals(2, oneOf.size());
        assertEquals(Set.of("router_tool", "extract_plan"), branchNames(oneOf));
    }

    @Test
    void wrappedScalarsAreFlattened() {
        JsonObject schema = GrammarSchema.build(List.of(
            fnSchema("router_tool",
                wrappedScalar("fromFacilityId", "string"),
                wrappedScalar("departureTimeSeconds", "number"))));
        JsonObject argProps = argumentsProperties(oneOfBranches(schema).get(0).getAsJsonObject());
        assertEquals("string", argProps.getAsJsonObject("fromFacilityId").get("type").getAsString());
        assertEquals("number", argProps.getAsJsonObject("departureTimeSeconds").get("type").getAsString());
    }

    @Test
    void complexNestedArgsCollapseToFreeFormType() {
        // Stress: PlanDTO-shaped arg with nested oneOf inside items
        JsonObject schema = GrammarSchema.build(List.of(
            fnSchema("extract_plan", planLikeProp("plan"))));
        JsonObject argProps = argumentsProperties(oneOfBranches(schema).get(0).getAsJsonObject());
        JsonObject planSchema = argProps.getAsJsonObject("plan");
        assertEquals("object", planSchema.get("type").getAsString());
        assertFalse(planSchema.has("properties"), "complex nested structure must be dropped");
        assertFalse(planSchema.has("oneOf"), "deeply nested oneOf must be dropped");
    }

    @Test
    void schemaSizeStaysBoundedForAllRegisteredTools() {
        // The 6 registered tools, with extract_plan + validate_timing carrying PlanDTO
        JsonObject schema = GrammarSchema.build(List.of(
            fnSchema("router_tool",
                wrappedScalar("fromFacilityId", "string"),
                wrappedScalar("toFacilityId", "string"),
                wrappedScalar("mode", "string"),
                wrappedScalar("departureTimeSeconds", "number")),
            fnSchema("extract_plan", planLikeProp("plan")),
            fnSchema("validate_timing", planLikeProp("plan")),
            fnSchema("activity_chain_summary"),
            fnSchema("available_modes", wrappedScalar("fromFacilityId", "string")),
            fnSchema("pull_additional_context", scalarProp("text", "string"))
        ));
        int len = schema.toString().length();
        // Empirically, Ollama rejects intermittently above ~10 KB for nested oneOf.
        assertTrue(len < 4000, "schema too large for backend validators: " + len);
    }

    @Test
    void schemaHasNoDescriptionsAfterFlattening() {
        // Some structured-output backends choke on long descriptions.
        JsonObject schema = GrammarSchema.build(List.of(
            fnSchemaWithDescription("router_tool", "should be stripped",
                scalarProp("fromFacilityId", "string"))));
        String s = schema.toString();
        assertFalse(s.contains("should be stripped"),
            "tool description leaked into schema: " + s);
    }

    @Test
    void responseFormatEnvelopeIsOpenAiCompatible() {
        JsonObject schema = GrammarSchema.build(List.of(fnSchema("router_tool")));
        JsonObject envelope = GrammarSchema.asResponseFormat(schema, "ToolCall");
        assertEquals("json_schema", envelope.get("type").getAsString());
        JsonObject inner = envelope.getAsJsonObject("json_schema");
        assertEquals("ToolCall", inner.get("name").getAsString());
        assertNotNull(inner.get("schema"));
        assertFalse(inner.has("strict"), "strict:true breaks Ollama for our tool schemas");
    }

    // --- helpers ---

    private static JsonObject fnSchema(String name, JsonObject... props) {
        return fnSchemaWithDescription(name, null, props);
    }

    private static JsonObject fnSchemaWithDescription(String name, String description, JsonObject... props) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        if (description != null) parameters.addProperty("description", description);
        JsonObject properties = new JsonObject();
        for (JsonObject p : props) properties.add(p.get("__name").getAsString(), withoutMarker(p));
        parameters.add("properties", properties);

        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.add("parameters", parameters);
        return fn;
    }

    private static JsonObject scalarProp(String name, String type) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("__name", name);
        return p;
    }

    private static JsonObject wrappedScalar(String name, String type) {
        JsonObject value = new JsonObject();
        value.addProperty("type", type);
        JsonObject inner = new JsonObject();
        inner.add("value", value);
        JsonObject p = new JsonObject();
        p.addProperty("type", "object");
        p.add("properties", inner);
        JsonArray req = new JsonArray();
        req.add("value");
        p.add("required", req);
        p.addProperty("__name", name);
        return p;
    }

    private static JsonObject planLikeProp(String name) {
        // Mimic the giant nested PlanDTO: object with properties.elements: array.items.oneOf
        JsonObject items = new JsonObject();
        items.addProperty("type", "object");
        JsonObject act = new JsonObject(); act.addProperty("type", "object");
        JsonObject leg = new JsonObject(); leg.addProperty("type", "object");
        JsonArray oneOf = new JsonArray(); oneOf.add(act); oneOf.add(leg);
        items.add("oneOf", oneOf);

        JsonObject elements = new JsonObject();
        elements.addProperty("type", "array");
        elements.add("items", items);
        elements.addProperty("description", "list of plan elements");

        JsonObject planProps = new JsonObject();
        planProps.add("elements", elements);

        JsonObject plan = new JsonObject();
        plan.addProperty("type", "object");
        plan.addProperty("description", "MATSim plan with deeply nested structure");
        plan.add("properties", planProps);
        plan.addProperty("__name", name);
        return plan;
    }

    private static JsonObject withoutMarker(JsonObject p) {
        JsonObject copy = p.deepCopy();
        copy.remove("__name");
        return copy;
    }

    private static JsonArray oneOfBranches(JsonObject schema) {
        return schema.getAsJsonObject("properties")
            .getAsJsonObject("tool_call")
            .getAsJsonArray("oneOf");
    }

    private static Set<String> branchNames(JsonArray oneOf) {
        Set<String> names = new java.util.HashSet<>();
        for (JsonElement e : oneOf) {
            names.add(e.getAsJsonObject().getAsJsonObject("properties")
                .getAsJsonObject("name").get("const").getAsString());
        }
        return names;
    }

    private static JsonObject argumentsProperties(JsonObject branch) {
        JsonObject args = branch.getAsJsonObject("properties").getAsJsonObject("arguments");
        return args.has("properties") ? args.getAsJsonObject("properties") : new JsonObject();
    }
}
