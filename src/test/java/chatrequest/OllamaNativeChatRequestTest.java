package chatrequest;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import chatcommons.IChatMessage;
import chatcommons.Role;

import static org.junit.jupiter.api.Assertions.*;

class OllamaNativeChatRequestTest {

    private static final OllamaNativeChatRequest REQ = new OllamaNativeChatRequest();

    private static List<IChatMessage> singleUserMessage() {
        return List.<IChatMessage>of(new SimpleRequestMessage(Role.USER, "hello"));
    }

    @Test
    void emitsNumCtxWhenContextWindowPositive() {
        String body = REQ.serializeToHttpBody(
                singleUserMessage(),
                Collections.emptyList(),
                Collections.emptyMap(),
                "auto",
                0.3,
                4096,
                12288,
                "qwen3.5",
                false,
                true);

        JsonObject payload = JsonParser.parseString(body).getAsJsonObject();
        JsonObject options = payload.getAsJsonObject("options");
        assertNotNull(options, "options block must exist");
        assertTrue(options.has("num_ctx"), "num_ctx must be emitted when contextWindow > 0");
        assertEquals(12288, options.get("num_ctx").getAsInt());
        assertEquals(4096, options.get("num_predict").getAsInt());
        assertEquals(0.3, options.get("temperature").getAsDouble(), 1e-9);
    }

    @Test
    void omitsNumCtxWhenContextWindowZero() {
        String body = REQ.serializeToHttpBody(
                singleUserMessage(),
                Collections.emptyList(),
                Collections.emptyMap(),
                "auto",
                0.3,
                4096,
                0,
                "qwen3.5",
                false,
                true);

        JsonObject payload = JsonParser.parseString(body).getAsJsonObject();
        JsonObject options = payload.getAsJsonObject("options");
        assertFalse(options.has("num_ctx"),
                "num_ctx must not be emitted when contextWindow <= 0 (let the backend default apply)");
    }

    @Test
    void thinkFlagSerialized() {
        String body = REQ.serializeToHttpBody(
                singleUserMessage(),
                Collections.emptyList(),
                Collections.emptyMap(),
                "auto",
                0.3,
                4096,
                8192,
                "qwen3.5",
                false,
                true);

        JsonObject payload = JsonParser.parseString(body).getAsJsonObject();
        assertTrue(payload.get("think").getAsBoolean(),
                "think flag must flow through to the payload");
    }
}
