package chatresponse.grammar;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import tools.IToolCall;

/**
 * Parses a grammar-constrained assistant content string (a JSON object with
 * {@code thought} and {@code tool_call}) into a synthetic {@link IToolCall}.
 *
 * <p>The content matches the schema built by
 * {@link chatrequest.grammar.GrammarSchema#build(List)}. The sampler has
 * guaranteed validity, so a parse failure here indicates a non-grammar-mode
 * response (backend didn't honor the format) — caller should fall back.
 */
public final class GrammarResponseParser {

    private GrammarResponseParser() {}

    /** Returns a synthetic tool call, or null if the content isn't a constrained object. */
    public static IToolCall parse(String content) {
        if (content == null || content.isBlank()) return null;
        JsonObject root;
        try {
            root = JsonParser.parseString(content).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
        if (!root.has("tool_call") || !root.get("tool_call").isJsonObject()) return null;

        JsonObject tc = root.getAsJsonObject("tool_call");
        if (!tc.has("name") || !tc.has("arguments")) return null;

        String name = tc.get("name").getAsString();
        String args = tc.get("arguments").toString();
        String id = "grammar-" + UUID.randomUUID();
        return new SimpleToolCall(id, name, args);
    }

    private record SimpleToolCall(String id, String name, String args) implements IToolCall {
        @Override public String getId() { return id; }
        @Override public String getName() { return name; }
        @Override public String getArguments() { return args; }
    }
}
