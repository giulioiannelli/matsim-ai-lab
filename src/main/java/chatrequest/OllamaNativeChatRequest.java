package chatrequest;

import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import chatcommons.IChatMessage;
import chatresponse.IResponseMessage;
import tools.IToolCall;
import tools.IToolResponse;

/**
 * Request builder for Ollama's native {@code /api/chat} endpoint.
 *
 * <p>Unlike the OpenAI-compatibility shim at {@code /v1/chat/completions}, this
 * endpoint speaks the format local reasoning models (qwen3.5, deepseek-r1-distill)
 * were trained on, so tool calls reliably land in the structured {@code tool_calls}
 * field instead of leaking into {@code content}.
 *
 * <p>Payload shape (relevant fields):
 * <pre>
 * {
 *   "model":   "qwen3.5",
 *   "stream":  false,
 *   "think":   true,                         // Ollama 0.6+; default on for reasoning models
 *   "messages":[...],
 *   "tools":   [{"type":"function","function":{...}}],
 *   "format":  { ... JSON schema ... },     // optional, grammar-constrained output
 *   "options": {"temperature": 0.3, "num_predict": 4096}
 * }
 * </pre>
 */
public class OllamaNativeChatRequest implements IChatCompletionRequest {

    private static final Gson gson = new Gson();

    @Override
    public String serializeToHttpBody(List<IChatMessage> messages,
                                      List<JsonObject> tools,
                                      Map<String, Boolean> toolIfDummy,
                                      String toolChoice,
                                      double temperature,
                                      int maxTokens,
                                      int contextWindow,
                                      String modelName,
                                      boolean stream,
                                      boolean enableThinking) {

        JsonObject payload = new JsonObject();
        payload.addProperty("model", modelName);
        payload.addProperty("stream", stream);
        payload.addProperty("think", enableThinking);

        // Options block — native place for per-request runtime knobs.
        JsonObject options = new JsonObject();
        options.addProperty("temperature", temperature);
        options.addProperty("num_predict", maxTokens);
        if (contextWindow > 0) {
            options.addProperty("num_ctx", contextWindow);
        }
        payload.add("options", options);

        // Messages.
        JsonArray messageArray = new JsonArray();
        for (IChatMessage m : messages) {

            if (m instanceof IResponseMessage response) {
                JsonObject mJson = new JsonObject();
                mJson.addProperty("role", "assistant");
                mJson.addProperty("content", response.getContent() == null ? "" : response.getContent());

                if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
                    JsonArray toolCalls = new JsonArray();
                    for (IToolCall tc : response.getToolCalls()) {
                        if (!toolIfDummy.getOrDefault(tc.getName(), false)) {
                            JsonObject toolCall = new JsonObject();
                            JsonObject functionObj = new JsonObject();
                            functionObj.addProperty("name", tc.getName());
                            // Ollama native accepts arguments as a JSON object, not a string.
                            try {
                                functionObj.add("arguments",
                                        com.google.gson.JsonParser.parseString(tc.getArguments()).getAsJsonObject());
                            } catch (Exception e) {
                                functionObj.addProperty("arguments", tc.getArguments());
                            }
                            toolCall.add("function", functionObj);
                            toolCalls.add(toolCall);
                        }
                    }
                    mJson.add("tool_calls", toolCalls);
                }

                messageArray.add(mJson);
                continue;
            }

            if (m instanceof IRequestMessage request
                    && request.getToolResponses() != null
                    && !request.getToolResponses().isEmpty()) {
                for (IToolResponse<?> tr : request.getToolResponses()) {
                    if (tr.isForLLM()) {
                        JsonObject toolResponse = new JsonObject();
                        toolResponse.addProperty("role", "tool");
                        toolResponse.addProperty("content", tr.getResponseJson());
                        messageArray.add(toolResponse);
                    }
                }
                continue;
            }

            JsonObject mJson = new JsonObject();
            mJson.addProperty("role", m.getRole().name().toLowerCase());
            mJson.addProperty("content", m.getContent());
            messageArray.add(mJson);
        }
        payload.add("messages", messageArray);

        // Tools (OpenAI-shaped).
        if (tools != null && !tools.isEmpty()) {
            JsonArray toolArray = new JsonArray();
            for (JsonObject tool : tools) {
                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("type", "function");
                wrapper.add("function", tool);
                toolArray.add(wrapper);
            }
            payload.add("tools", toolArray);
        }

        return gson.toJson(payload);
    }
}
