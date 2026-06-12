package chatresponse;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the capture of {@code message.reasoning} from OpenAI-compatible
 * endpoints (including Ollama's shim) plus the {@code <think>} tag fallback for
 * models that embed reasoning inline in content.
 */
class ReasoningCaptureTest {

    private static final Gson GSON = new Gson();

    @Test
    void capturesMessageLevelReasoning_withToolCall() {
        String json = """
            {"id":"x","object":"chat.completion","created":0,"model":"qwen3.5",
             "choices":[{"index":0,"finish_reason":"stop","message":{
                "role":"assistant",
                "content":"",
                "reasoning":"The plan has two legs. I should route each.",
                "tool_calls":[{"id":"t1","type":"function","function":{"name":"router_tool","arguments":"{}"}}]
             }}],
             "usage":{"prompt_tokens":100,"completion_tokens":30,"total_tokens":130}}
            """;

        OpenAiCompatChatResponse r = GSON.fromJson(json, OpenAiCompatChatResponse.class);
        r.postBuildCleanup();

        assertEquals("The plan has two legs. I should route each.", r.getReasoning());
        assertNotNull(r.getMessage());
        assertEquals("The plan has two legs. I should route each.", r.getMessage().getReasoning());
        assertEquals(1, r.getMessage().getToolCalls().size());
    }

    @Test
    void capturesMessageLevelReasoning_noToolCall() {
        String json = """
            {"id":"x","object":"chat.completion","created":0,"model":"deepseek-r1",
             "choices":[{"index":0,"finish_reason":"stop","message":{
                "role":"assistant",
                "content":"",
                "reasoning":"Step 1: read plan. Step 2: call router.",
                "tool_calls":[]
             }}],
             "usage":{"prompt_tokens":50,"completion_tokens":20,"total_tokens":70}}
            """;

        OpenAiCompatChatResponse r = GSON.fromJson(json, OpenAiCompatChatResponse.class);
        r.postBuildCleanup();

        assertEquals("Step 1: read plan. Step 2: call router.", r.getReasoning());
        assertEquals("Step 1: read plan. Step 2: call router.", r.getMessage().getReasoning());
    }

    @Test
    void fallsBackToThinkTags_whenNoSeparateReasoningField() {
        String json = """
            {"id":"x","object":"chat.completion","created":0,"model":"qwen2.5",
             "choices":[{"index":0,"finish_reason":"stop","message":{
                "role":"assistant",
                "content":"<think>deliberating</think>Final answer.",
                "tool_calls":[]
             }}],
             "usage":{"prompt_tokens":10,"completion_tokens":10,"total_tokens":20}}
            """;

        OpenAiCompatChatResponse r = GSON.fromJson(json, OpenAiCompatChatResponse.class);
        r.postBuildCleanup();

        assertEquals("deliberating", r.getReasoning());
        assertEquals("Final answer.", r.getMessage().getContent());
    }

    @Test
    void noReasoning_whenAbsent() {
        String json = """
            {"id":"x","object":"chat.completion","created":0,"model":"qwen2.5",
             "choices":[{"index":0,"finish_reason":"stop","message":{
                "role":"assistant",
                "content":"plain answer",
                "tool_calls":[]
             }}],
             "usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}
            """;

        OpenAiCompatChatResponse r = GSON.fromJson(json, OpenAiCompatChatResponse.class);
        r.postBuildCleanup();

        assertTrue(r.getReasoning() == null);
        assertTrue(r.getMessage().getReasoning() == null);
        assertEquals("plain answer", r.getMessage().getContent());
    }
}
