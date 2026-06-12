package tools;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;

import chatrequest.IRequestMessage;
import rag.IVectorDB;

public interface IToolManager {

    /**
     * Registers a tool under its declared name. Overwrites any tool with the same name.
     */
    void registerTool(ITool<?> tool);

    /**
     * Retrieves a registered tool by name.
     * @throws IllegalArgumentException if no tool is registered under that name
     */
    ITool<?> getByName(String name);

    /**
     * Returns a list of OpenAI-compatible tool specifications for all registered tools.
     * Each JsonObject can be used directly in the "tools" section of a chat request.
     */
    List<JsonObject> getAllToolSchemas();

    /**
     * Returns OpenAI-compatible tool specifications for the subset of tools whose names
     * are in the given set. Names not matching any registered tool are silently ignored.
     * If the set is null, behaves like {@link #getAllToolSchemas()}.
     *
     * Used by per-round tool filtering (staged tool exposure) to reduce the schema
     * load on small models.
     */
    default List<JsonObject> getSchemasFor(Set<String> names) {
        if (names == null) return getAllToolSchemas();
        List<JsonObject> all = getAllToolSchemas();
        List<JsonObject> filtered = new java.util.ArrayList<>();
        for (JsonObject schema : all) {
            if (schema.has("name") && names.contains(schema.get("name").getAsString())) {
                filtered.add(schema);
            } else if (schema.has("function")) {
                // Some backends wrap: {"type":"function","function":{"name":...}}
                JsonObject func = schema.getAsJsonObject("function");
                if (func.has("name") && names.contains(func.get("name").getAsString())) {
                    filtered.add(schema);
                }
            }
        }
        return filtered;
    }

    /**
     * Executes a single tool call using its registered implementation.
     * The result includes a typed output object and can be inspected via isForLLM().
     *
     * @param call The tool call (name and arguments) received from the LLM with access to the vector database to insert new documents. 
     * @return The tool's typed response (always returned, dummy tools included)
     */
    <T> IToolResponse<T> runToolCall(IToolCall call, IVectorDB vectorDB, Map<String, Object> context);

    /**
     * Executes a batch of tool calls and returns all responses.
     * Use isForLLM() on each response to decide what to inject back into the LLM.
     *
     * @param calls The list of tool calls to run with access to the vector database to insert new documents
     * @return All tool responses (dummy and non-dummy included)
     */
    List<IToolResponse<?>> runToolCalls(List<IToolCall> calls, IVectorDB vectorDB, Map<String,Object> context);

    /**
     * Builds a single tool role message containing all non-dummy tool responses.
     * This can be injected into the LLM conversation history as one `tool` message.
     *
     * If all responses are dummy (i.e., isForLLM() == false), this returns null.
     *
     * @param calls The list of tool calls to execute
     * @return A single IRequestMessage with role = "tool", or null if no responses should be sent
     */
    IRequestMessage buildToolResponseMessage(List<IToolCall> calls, IVectorDB vectorDB, Map<String,Object> context);
    
    
    Map<String,Boolean> getIfToolDummy();
}
