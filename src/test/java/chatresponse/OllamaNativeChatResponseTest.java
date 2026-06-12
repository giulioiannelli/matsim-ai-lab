package chatresponse;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the mapping of Ollama's native {@code /api/chat} response into
 * {@link OllamaNativeChatResponse} — in particular that {@code message.thinking}
 * reaches {@code getReasoning()} and that {@code tool_calls} arrive in the
 * structured field (the whole point of routing through the native endpoint).
 */
class OllamaNativeChatResponseTest {

    private static final Gson GSON = new Gson();

    @Test
    void capturesThinkingField() {
        String json = """
            {
              "model":"qwen3.5",
              "created_at":"2026-04-20T12:00:00Z",
              "message":{
                "role":"assistant",
                "content":"4",
                "thinking":"Think step by step: 2+2=4."
              },
              "done":true,"done_reason":"stop",
              "total_duration":1000000000,"load_duration":10000000,
              "prompt_eval_count":20,"prompt_eval_duration":100000000,
              "eval_count":10,"eval_duration":200000000
            }
            """;
        OllamaNativeChatResponse r = GSON.fromJson(json, OllamaNativeChatResponse.class);
        r.postBuildCleanup();

        assertEquals("Think step by step: 2+2=4.", r.getReasoning());
        assertEquals("Think step by step: 2+2=4.", r.getMessage().getReasoning());
        assertEquals("4", r.getMessage().getContent());
        assertEquals(0, r.getMessage().getToolCalls().size());
        assertEquals(20, r.getUsage().getPromptTokens());
        assertEquals(10, r.getUsage().getCompletionTokens());
        assertEquals(30, r.getUsage().getTotalTokens());
    }

    @Test
    void capturesToolCallsInStructuredField_noContentSpill() {
        String json = """
            {
              "model":"qwen3.5",
              "message":{
                "role":"assistant",
                "content":"",
                "thinking":"I should call extract_plan.",
                "tool_calls":[{
                  "function":{
                    "index":0,
                    "name":"extract_plan",
                    "arguments":{"plan":{"elements":[{"type":"home"}]}}
                  }
                }]
              },
              "done":true,"done_reason":"stop",
              "prompt_eval_count":30,"eval_count":15
            }
            """;
        OllamaNativeChatResponse r = GSON.fromJson(json, OllamaNativeChatResponse.class);
        r.postBuildCleanup();

        assertEquals(1, r.getToolCalls().size());
        assertEquals("extract_plan", r.getToolCalls().get(0).getName());
        // Arguments must be a JSON string (OpenAI calling convention) so downstream
        // tool plumbing stays unchanged — the shape test is that it parses.
        String args = r.getToolCalls().get(0).getArguments();
        assertNotNull(args);
        assertTrue(args.contains("\"plan\""));
        assertTrue(args.contains("\"elements\""));
        // Content must be empty — the whole point of switching endpoints is that tool
        // calls land in tool_calls, not in content.
        assertEquals("", r.getMessage().getContent());
    }

    @Test
    void toolCallArgumentsSerializedAsString_notAsObject() {
        String json = """
            {
              "model":"qwen3.5",
              "message":{
                "role":"assistant",
                "content":"",
                "tool_calls":[{
                  "function":{"name":"router_tool","arguments":{"from":"a","to":"b","mode":"car"}}
                }]
              },
              "done":true
            }
            """;
        OllamaNativeChatResponse r = GSON.fromJson(json, OllamaNativeChatResponse.class);
        r.postBuildCleanup();

        String args = r.getToolCalls().get(0).getArguments();
        // Should parse back into a JSON object with those 3 keys.
        com.google.gson.JsonObject parsed = com.google.gson.JsonParser.parseString(args).getAsJsonObject();
        assertEquals("a", parsed.get("from").getAsString());
        assertEquals("b", parsed.get("to").getAsString());
        assertEquals("car", parsed.get("mode").getAsString());
    }

    @Test
    void missingThinkingField_isTolerated() {
        String json = """
            {
              "model":"qwen2.5:7b",
              "message":{"role":"assistant","content":"hi","tool_calls":[]},
              "done":true,"eval_count":2,"prompt_eval_count":3
            }
            """;
        OllamaNativeChatResponse r = GSON.fromJson(json, OllamaNativeChatResponse.class);
        r.postBuildCleanup();

        assertNull(r.getReasoning());
        assertEquals("hi", r.getMessage().getContent());
    }

    @Test
    void doneReasonAndTimingSurfacedInMetadata() {
        String json = """
            {
              "model":"qwen3.5",
              "message":{"role":"assistant","content":""},
              "done":true,"done_reason":"length",
              "total_duration":5000000000,"eval_duration":4500000000
            }
            """;
        OllamaNativeChatResponse r = GSON.fromJson(json, OllamaNativeChatResponse.class);
        r.postBuildCleanup();

        assertEquals("length", r.getMetadata().get("done_reason"));
        assertEquals(5000000000L, r.getMetadata().get("total_duration_ns"));
    }
}
