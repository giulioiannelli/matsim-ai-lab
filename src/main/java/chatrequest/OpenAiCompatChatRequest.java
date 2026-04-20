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
 * Request builder for the OpenAI Chat Completions wire format.
 *
 * <p>Works with any server that exposes the {@code /v1/chat/completions} shape:
 * OpenAI's own API, LM Studio, Ollama's OpenAI-compatibility shim, vLLM,
 * llama.cpp server, LocalAI, OpenRouter, and similar.
 *
 * <p>For Ollama's richer native endpoint, see {@link OllamaNativeChatRequest}.
 */
public class OpenAiCompatChatRequest implements IChatCompletionRequest {

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

        // contextWindow is intentionally unused on this wire format: OpenAI-compat
        // backends infer context length from the loaded model and do not expose a
        // per-request knob. The parameter is kept on the interface so callers pass
        // one argument list regardless of backend.

        JsonObject payload = new JsonObject();
        payload.addProperty("model", modelName);
        payload.addProperty("temperature", temperature);
        payload.addProperty("max_tokens", maxTokens);
        payload.addProperty("stream", stream);
        payload.addProperty("enable_thinking", enableThinking);

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
                            toolCall.addProperty("id", tc.getId());
                            toolCall.addProperty("type", "function");

                            JsonObject functionObj = new JsonObject();
                            functionObj.addProperty("name", tc.getName());
                            functionObj.addProperty("arguments", tc.getArguments());
                            toolCall.add("function", functionObj);
                            toolCalls.add(toolCall);
                        }
                    }
                    if (toolCalls.size() > 0) {
                        mJson.add("tool_calls", toolCalls);
                    }
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
                        toolResponse.addProperty("tool_call_id", tr.getToolCallId());
                        toolResponse.addProperty("name", tr.getName());
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

        if (tools != null && !tools.isEmpty()) {
            JsonArray toolArray = new JsonArray();
            for (JsonObject tool : tools) {
                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("type", "function");
                wrapper.add("function", tool);
                toolArray.add(wrapper);
            }
            payload.add("tools", toolArray);

            if (toolChoice != null && !toolChoice.isBlank()) {
                payload.addProperty("tool_choice", toolChoice);
            }
        }

        return gson.toJson(payload);
    }
}
