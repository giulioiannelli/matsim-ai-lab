package matsimBinding.profile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.yaml.snakeyaml.Yaml;

/**
 * Loads per-model overrides from a YAML file shaped like:
 * <pre>
 * profiles:
 *   "qwen3.5":
 *     maxTokens: 4096
 *     temperature: 0.3
 *     enableThinking: true
 *     isReasoning: true
 *     thinkingTokenCap: 2048
 * </pre>
 *
 * <p>The loader never throws on missing files — a missing or unreadable file
 * produces an empty registry, preserving baseline behavior (all lookups return
 * {@link Optional#empty()}).
 */
public final class ModelProfileLoader {

    private ModelProfileLoader() {}

    /** Load from a filesystem path. Returns empty registry if the file is absent. */
    public static Map<String, ModelProfile> load(Path path) {
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
            return Collections.emptyMap();
        }
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in);
        } catch (IOException e) {
            System.err.println("ModelProfileLoader: failed to read " + path + ": " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Parse a YAML stream directly. Exposed for tests. */
    @SuppressWarnings("unchecked")
    static Map<String, ModelProfile> parse(InputStream in) {
        Object root = new Yaml().load(in);
        if (!(root instanceof Map<?, ?> rootMap)) return Collections.emptyMap();

        Object profilesNode = rootMap.get("profiles");
        if (!(profilesNode instanceof Map<?, ?> profilesMap)) return Collections.emptyMap();

        Map<String, ModelProfile> out = new HashMap<>();
        for (Map.Entry<?, ?> e : profilesMap.entrySet()) {
            if (!(e.getKey() instanceof String name)) continue;
            if (!(e.getValue() instanceof Map<?, ?> fields)) continue;
            out.put(name, readProfile(name, (Map<String, Object>) fields));
        }
        return Collections.unmodifiableMap(out);
    }

    private static ModelProfile readProfile(String name, Map<String, Object> fields) {
        int maxTokens       = intOf(fields, "maxTokens", 2048);
        int contextWindow   = intOf(fields, "contextWindow", 8192);
        double temperature  = doubleOf(fields, "temperature", 0.7);
        boolean thinking    = boolOf(fields, "enableThinking", false);
        boolean reasoning   = boolOf(fields, "isReasoning", false);
        int thinkingCap     = intOf(fields, "thinkingTokenCap", 0);
        String endpointStyle = strOf(fields, "endpointStyle", ModelProfile.ENDPOINT_OPENAI_COMPAT);
        return new ModelProfile(name, maxTokens, contextWindow, temperature, thinking, reasoning, thinkingCap, endpointStyle);
    }

    private static int intOf(Map<String, Object> m, String k, int dflt) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : dflt;
    }

    private static double doubleOf(Map<String, Object> m, String k, double dflt) {
        Object v = m.get(k);
        return v instanceof Number n ? n.doubleValue() : dflt;
    }

    private static boolean boolOf(Map<String, Object> m, String k, boolean dflt) {
        Object v = m.get(k);
        return v instanceof Boolean b ? b : dflt;
    }

    private static String strOf(Map<String, Object> m, String k, String dflt) {
        Object v = m.get(k);
        return v instanceof String s && !s.isBlank() ? s : dflt;
    }
}
