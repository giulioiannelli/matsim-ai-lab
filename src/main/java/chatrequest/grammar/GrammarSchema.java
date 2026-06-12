package chatrequest.grammar;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Builds a JSON-Schema payload usable as OpenAI-compatible {@code response_format}
 * (or Ollama's {@code format}) that constrains the LLM to emit one of a fixed
 * set of tool calls. The schema uses {@code oneOf} over per-tool branches, each
 * branch pinned by a {@code const} on the tool name.
 *
 * <p>The resulting JSON looks like:
 * <pre>{
 *   "type":"object",
 *   "properties":{
 *     "thought":{"type":"string"},
 *     "tool_call":{ "oneOf":[ {name:"X",args:{...}}, ... ] }
 *   },
 *   "required":["thought","tool_call"]
 * }</pre>
 */
public final class GrammarSchema {

    private GrammarSchema() {}

    /**
     * Builds a constrained output schema with one {@code oneOf} branch per
     * registered tool. Each branch pins the tool name with {@code const} and
     * exposes the tool's flattened argument schema (with wrapped scalars
     * unwrapped to their primitive types so the LLM emits flat keys).
     */
    public static JsonObject build(List<JsonObject> toolSchemas) {
        JsonArray oneOf = new JsonArray();
        for (JsonObject fn : toolSchemas) {
            oneOf.add(branchFor(fn));
        }

        JsonObject toolCall = new JsonObject();
        toolCall.add("oneOf", oneOf);

        JsonObject thought = new JsonObject();
        thought.addProperty("type", "string");

        JsonObject properties = new JsonObject();
        properties.add("thought", thought);
        properties.add("tool_call", toolCall);

        JsonArray required = new JsonArray();
        required.add("thought");
        required.add("tool_call");

        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        root.add("properties", properties);
        root.add("required", required);
        return root;
    }

    private static JsonObject branchFor(JsonObject fn) {
        String toolName = fn.has("name") ? fn.get("name").getAsString() : "unknown";
        JsonObject parameters = fn.has("parameters") && fn.get("parameters").isJsonObject()
            ? fn.getAsJsonObject("parameters")
            : new JsonObject();
        JsonObject flatParameters = flattenWrappedScalars(parameters);

        JsonObject nameProp = new JsonObject();
        nameProp.addProperty("const", toolName);

        JsonObject branchProps = new JsonObject();
        branchProps.add("name", nameProp);
        branchProps.add("arguments", flatParameters);

        JsonArray required = new JsonArray();
        required.add("name");
        required.add("arguments");

        JsonObject branch = new JsonObject();
        branch.addProperty("type", "object");
        branch.add("properties", branchProps);
        branch.add("required", required);
        return branch;
    }

    /**
     * Simplifies a tool's parameters schema for the grammar:
     *   - wrapped scalar DTOs ({@code {type:object, properties:{value:{type:X}}}})
     *     unwrap to their primitive type
     *   - complex object/array properties (anything deeper than a primitive)
     *     collapse to free-form {@code {type:T}} so the grammar enforces tool
     *     name + primitive arg names without choking the backend's validator
     *     on deeply nested {@code oneOf} structures (e.g. PlanDTO)
     */
    static JsonObject flattenWrappedScalars(JsonObject schema) {
        if (schema == null || !schema.has("properties")) return schema;
        JsonObject out = schema.deepCopy();
        out.remove("description");
        JsonObject props = out.getAsJsonObject("properties");
        for (Map.Entry<String, JsonElement> e : props.entrySet()) {
            JsonElement v = e.getValue();
            if (!v.isJsonObject()) continue;
            e.setValue(simplifyProperty(v.getAsJsonObject()));
        }
        return out;
    }

    private static JsonElement simplifyProperty(JsonObject prop) {
        JsonElement unwrapped = unwrapScalar(prop);
        if (unwrapped != null) return unwrapped;
        String t = asString(prop.get("type"));
        if ("string".equals(t) || "number".equals(t) || "integer".equals(t) || "boolean".equals(t)) {
            JsonObject keep = new JsonObject();
            keep.addProperty("type", t);
            return keep;
        }
        // Complex object/array: keep only the type, drop nested structure.
        JsonObject simple = new JsonObject();
        simple.addProperty("type", t != null ? t : "object");
        return simple;
    }

    /**
     * Returns the inner scalar schema if {@code prop} is a wrapped DTO of the
     * form {@code {type:object, properties:{value:{type:X}}}}, otherwise null.
     */
    private static JsonElement unwrapScalar(JsonObject prop) {
        if (!"object".equals(asString(prop.get("type")))) return null;
        if (!prop.has("properties")) return null;
        JsonObject inner = prop.getAsJsonObject("properties");
        if (inner.size() != 1 || !inner.has("value")) return null;
        JsonElement valueSchema = inner.get("value");
        if (!valueSchema.isJsonObject()) return null;
        String t = asString(valueSchema.getAsJsonObject().get("type"));
        if ("string".equals(t) || "number".equals(t) || "integer".equals(t) || "boolean".equals(t)) {
            JsonObject keep = new JsonObject();
            keep.addProperty("type", t);
            return keep;
        }
        return null;
    }

    private static String asString(JsonElement el) {
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    /** Wraps the schema into the OpenAI {@code response_format} envelope. */
    public static JsonObject asResponseFormat(JsonObject schema, String name) {
        JsonObject json_schema = new JsonObject();
        json_schema.addProperty("name", name);
        json_schema.add("schema", schema);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_schema");
        responseFormat.add("json_schema", json_schema);
        return responseFormat;
    }
}
