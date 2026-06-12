package chatrequest.grammar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import chatcommons.Role;
import chatresponse.IChatCompletionResponse;
import chatresponse.IResponseMessage;
import chatresponse.IUsage;
import chatresponse.grammar.GrammarResponseParser;
import tools.IToolCall;

/**
 * Opt-in feature: swap free tool calling for JSON-schema grammar-constrained output.
 *
 * <p>Enabled via {@code -Dmatsim.llm.grammar=true}. Off by default — when off,
 * all methods below are no-ops and the caller behaves as baseline.
 *
 * <p>The class is a thin adapter: it mutates an already-built request body to
 * replace {@code tools} with {@code response_format}, and post-processes the
 * assistant content back into a synthetic tool call.
 */
public final class GrammarMode {

    /** Enable via {@code -Dmatsim.llm.grammar=true}. */
    public static final boolean ENABLED = Boolean.getBoolean("matsim.llm.grammar");

    private GrammarMode() {}

    /**
     * Rewrites an already-serialized request body, stripping {@code tools} and
     * adding {@code response_format} built from the given tool schemas. Returns
     * the original body unchanged if grammar mode is disabled or the schemas
     * are empty.
     */
    public static String rewriteRequestBody(String body, List<JsonObject> toolSchemas) {
        if (!ENABLED || toolSchemas == null || toolSchemas.isEmpty()) return body;
        JsonObject payload = JsonParser.parseString(body).getAsJsonObject();
        payload.remove("tools");
        payload.remove("tool_choice");
        payload.add("response_format",
            GrammarSchema.asResponseFormat(GrammarSchema.build(toolSchemas), "ToolCall"));
        return payload.toString();
    }

    /**
     * Returns a response that surfaces a synthetic tool call parsed from the
     * assistant content (which was constrained by the response_format schema).
     * No-op when grammar mode is disabled or a real tool call is already present.
     */
    public static IChatCompletionResponse adaptResponse(IChatCompletionResponse response) {
        if (!ENABLED || response == null) return response;
        IResponseMessage msg = response.getMessage();
        if (msg == null) return response;
        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) return response;

        IToolCall synthetic = GrammarResponseParser.parse(msg.getContent());
        if (synthetic == null) return response;

        return new ResponseWithSyntheticCall(response, synthetic);
    }

    /** Decorator injecting a synthetic tool call while passing usage/reasoning through. */
    private static final class ResponseWithSyntheticCall implements IChatCompletionResponse {
        private final IChatCompletionResponse delegate;
        private final IResponseMessage wrappedMessage;

        ResponseWithSyntheticCall(IChatCompletionResponse delegate, IToolCall synthetic) {
            this.delegate = delegate;
            IResponseMessage inner = delegate.getMessage();
            List<IToolCall> calls = new ArrayList<>();
            calls.add(synthetic);
            this.wrappedMessage = new IResponseMessage() {
                @Override public Role getRole() { return inner != null ? inner.getRole() : Role.ASSISTANT; }
                @Override public String getContent() { return inner != null ? inner.getContent() : ""; }
                @Override public List<IToolCall> getToolCalls() { return calls; }
                @Override public boolean ifEnableThinking() { return inner != null && inner.ifEnableThinking(); }
            };
        }

        @Override public IResponseMessage getMessage() { return wrappedMessage; }
        @Override public List<IToolCall> getToolCalls() { return wrappedMessage.getToolCalls(); }
        @Override public IUsage getUsage() { return delegate.getUsage(); }
        @Override public String getModel() { return delegate.getModel(); }
        @Override public Map<String, Object> getMetadata() { return delegate.getMetadata(); }
        @Override public String getReasoning() { return delegate.getReasoning(); }
        @Override public void postBuildCleanup() { delegate.postBuildCleanup(); }
    }
}
