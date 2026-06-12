package chatresponse;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import chatcommons.Role;
import tools.IToolCall;

/**
 * Parser for Ollama's native {@code /api/chat} response format.
 *
 * <p>Key shape differences from {@link OpenAiCompatChatResponse}:
 * <ul>
 *   <li>No {@code choices[]} wrapper — message lives at top level as {@code message}.</li>
 *   <li>Reasoning comes back as {@code message.thinking} (not {@code message.reasoning}).</li>
 *   <li>Token counts are top-level fields ({@code prompt_eval_count}, {@code eval_count})
 *       rather than nested in a {@code usage} object.</li>
 *   <li>Completion termination reason is top-level {@code done_reason}
 *       (values include {@code "stop"}, {@code "length"}, {@code "load"}).</li>
 *   <li>Tool-call arguments come back as a JSON object (not the OpenAI-style
 *       string-containing-JSON), so we serialize on read.</li>
 * </ul>
 */
public class OllamaNativeChatResponse implements IChatCompletionResponse {

    private String model;
    private String created_at;
    private boolean done;
    private String done_reason;

    // Timing (nanoseconds in Ollama's output; we don't surface them in ChatStats today).
    @SerializedName("total_duration")       private long totalDuration;
    @SerializedName("load_duration")        private long loadDuration;
    @SerializedName("prompt_eval_count")    private int  promptEvalCount;
    @SerializedName("prompt_eval_duration") private long promptEvalDuration;
    @SerializedName("eval_count")           private int  evalCount;
    @SerializedName("eval_duration")        private long evalDuration;

    private Message message;

    // Cached after postBuildCleanup so getReasoning() and the message agree.
    private transient String reasoning;

    public static final class Message implements IResponseMessage {
        private Role role;
        private String content;
        private String thinking;
        @SerializedName("tool_calls") private List<ToolCall> toolCalls;

        // Populated by OllamaNativeChatResponse.postBuildCleanup() so downstream
        // callers that read via getReasoning() still work.
        private transient String reasoning;

        @Override public Role getRole() { return role != null ? role : Role.ASSISTANT; }
        @Override public String getContent() { return content == null ? "" : content; }
        @Override public List<IToolCall> getToolCalls() {
            return toolCalls == null ? Collections.emptyList() : (List<IToolCall>)(List<?>) toolCalls;
        }
        @Override public String getReasoning() { return reasoning != null ? reasoning : thinking; }
        @Override public boolean ifEnableThinking() { return false; }
    }

    public static final class ToolCall implements IToolCall {
        private String id;
        private String type;
        private Function function;

        @Override public String getId() {
            // Ollama native omits id in some versions; synthesize a stable-ish one.
            return id != null ? id : "call_" + Integer.toHexString(System.identityHashCode(this));
        }
        public String getType() { return type != null ? type : "function"; }
        public Function getFunction() { return function; }
        @Override public String getName() { return function == null ? null : function.name; }
        @Override public String getArguments() {
            if (function == null || function.arguments == null) return "{}";
            // OpenAI calling convention expects arguments to be a JSON-encoded STRING.
            // Ollama native returns a JSON object — serialize it back to a string here
            // so downstream tool plumbing sees the same shape as OpenAI-compat responses.
            JsonElement args = function.arguments;
            return args.isJsonPrimitive() ? args.getAsString() : args.toString();
        }
    }

    public static final class Function {
        public String name;
        public JsonElement arguments;
    }

    /** Adapter so existing consumers reading {@code getUsage().getReasoningTokens()} keep working. */
    private final class NativeUsage implements IUsage {
        @Override public int getPromptTokens()     { return promptEvalCount; }
        @Override public int getCompletionTokens() { return evalCount; }
        @Override public int getTotalTokens()      { return promptEvalCount + evalCount; }
        // Native endpoint does not split reasoning out; DefaultChatManager falls back to
        // estimating from reasoning text length via resolveReasoningTokens().
        @Override public int getReasoningTokens()  { return 0; }
    }

    @Override public IResponseMessage getMessage() { return message; }

    @Override public List<IToolCall> getToolCalls() {
        return message != null ? message.getToolCalls() : Collections.emptyList();
    }

    @Override public IUsage getUsage() { return new NativeUsage(); }

    @Override public String getModel() { return model; }

    @Override public Map<String, Object> getMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("created_at", created_at);
        meta.put("done", done);
        meta.put("done_reason", done_reason);
        meta.put("total_duration_ns", totalDuration);
        meta.put("eval_duration_ns", evalDuration);
        return meta;
    }

    @Override public void postBuildCleanup() {
        if (message == null) return;
        // Promote thinking → reasoning for uniform downstream access.
        if (message.thinking != null && !message.thinking.isBlank()) {
            message.reasoning = message.thinking.trim();
            this.reasoning = message.reasoning;
        }
        if (message.content != null) {
            message.content = message.content.trim();
        }
    }

    @Override public String getReasoning() {
        if (reasoning != null) return reasoning;
        return message != null ? message.thinking : null;
    }

    // Optional: expose JsonObject-shaped raw message for tests / transitional code.
    public JsonObject rawMessageAsJson() {
        JsonObject j = new JsonObject();
        if (message == null) return j;
        j.addProperty("role", message.getRole().name());
        j.addProperty("content", message.getContent());
        if (message.thinking != null) j.addProperty("thinking", message.thinking);
        return j;
    }
}
