package matsimBinding.profile;

/**
 * Per-model overrides resolved from {@code config/model-profiles.yaml}.
 *
 * <p>A profile is looked up by model name in {@link ModelProfileLoader}; if no
 * match exists, callers fall back to {@link matsimBinding.LLMConfigGroup} defaults.
 *
 * @param name             model name key (e.g. {@code qwen3.5}, {@code deepseek-r1-distill-qwen:7b})
 * @param maxTokens        cap on total completion tokens per round
 * @param contextWindow    total context window (prompt + completion) in tokens;
 *                         mapped to Ollama's {@code options.num_ctx}. Set above
 *                         the expected prompt+tools+history size to avoid silent
 *                         truncation on backends whose default is small (e.g.
 *                         Ollama's historical 2048).
 * @param temperature      sampling temperature
 * @param enableThinking   whether to send {@code think=true} / {@code enable_thinking=true}
 * @param isReasoning      model emits reasoning on a separate stream — grammar mode is unsafe
 * @param thinkingTokenCap soft cap on reasoning-token count per round; 0 = unlimited
 * @param endpointStyle    one of {@code openai_compat} (Ollama/LM Studio /v1 shim) or
 *                         {@code ollama_native} (Ollama {@code /api/chat}). Use
 *                         {@code ollama_native} for local reasoning models so tool
 *                         calls land in the structured {@code tool_calls} field
 *                         instead of leaking into content.
 */
public record ModelProfile(
        String name,
        int maxTokens,
        int contextWindow,
        double temperature,
        boolean enableThinking,
        boolean isReasoning,
        int thinkingTokenCap,
        String endpointStyle
) {
    public static final String ENDPOINT_OPENAI_COMPAT = "openai_compat";
    public static final String ENDPOINT_OLLAMA_NATIVE = "ollama_native";

    /** Default profile used when no model-specific profile is found. */
    public static ModelProfile defaults(String name) {
        return new ModelProfile(name, 2048, 8192, 0.7, false, false, 0, ENDPOINT_OPENAI_COMPAT);
    }
}
