package matsimBinding.profile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelProfileLoaderTest {

    @Test
    void parsesExpectedSchema() {
        String yaml = """
            profiles:
              "qwen3.5":
                maxTokens: 4096
                contextWindow: 12288
                temperature: 0.3
                enableThinking: true
                isReasoning: true
                thinkingTokenCap: 2048
              "qwen2.5:7b":
                maxTokens: 2048
                temperature: 0.5
                enableThinking: false
                isReasoning: false
            """;

        Map<String, ModelProfile> m = ModelProfileLoader.parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, m.size());
        ModelProfile r = m.get("qwen3.5");
        assertNotNull(r);
        assertEquals(4096, r.maxTokens());
        assertEquals(12288, r.contextWindow());
        assertEquals(0.3, r.temperature());
        assertTrue(r.enableThinking());
        assertTrue(r.isReasoning());
        assertEquals(2048, r.thinkingTokenCap());

        ModelProfile nr = m.get("qwen2.5:7b");
        assertFalse(nr.enableThinking());
        assertFalse(nr.isReasoning());
        assertEquals(0, nr.thinkingTokenCap());
        // contextWindow defaults to 8192 when the field is absent.
        assertEquals(8192, nr.contextWindow());
    }

    @Test
    void missingFile_returnsEmptyMap() {
        Map<String, ModelProfile> m = ModelProfileLoader.load(Path.of("/tmp/does-not-exist-xyz-4242.yaml"));
        assertTrue(m.isEmpty());
    }

    @Test
    void emptyInput_returnsEmpty() {
        Map<String, ModelProfile> m = ModelProfileLoader.parse(new ByteArrayInputStream(new byte[0]));
        assertTrue(m.isEmpty());
    }

    @Test
    void malformedInput_returnsEmpty() {
        String yaml = "not: profiles\nbut: something_else\n";
        Map<String, ModelProfile> m = ModelProfileLoader.parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        assertTrue(m.isEmpty());
    }

    @Test
    void loadsRealFileIfPresent() {
        Map<String, ModelProfile> m = ModelProfileLoader.load(Path.of("config/model-profiles.yaml"));
        // If the file exists (test run from repo root), we should find qwen3.5
        if (!m.isEmpty()) {
            assertTrue(m.containsKey("qwen3.5"));
            assertTrue(m.get("qwen3.5").isReasoning());
        }
    }
}
