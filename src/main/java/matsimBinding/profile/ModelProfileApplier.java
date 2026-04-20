package matsimBinding.profile;

import java.nio.file.Path;
import java.util.Map;

import matsimBinding.LLMConfigGroup;
import matsimBinding.LLMConfigGroup.BackendType;

/**
 * Resolves a {@link ModelProfile} from {@code model-profiles.yaml} (path taken
 * from {@link LLMConfigGroup#getModelProfilesPath()}) and applies it to the config.
 *
 * <p>Call exactly once per runner, after the {@link LLMConfigGroup} is otherwise
 * set up but before {@code controler.run()}. The applier mutates the config in
 * place:
 * <ul>
 *   <li>overrides {@code maxTokens} / {@code temperature} from the profile</li>
 *   <li>sets {@code reasoningModel}, {@code thinkingTokenCap}, {@code enableThinking}</li>
 *   <li>when {@code isReasoning=true} and the system property {@code matsim.llm.grammar}
 *       is truthy, prints a warning and clears the property so
 *       {@link chatrequest.grammar.GrammarMode#ENABLED} stays false — grammar
 *       mode is incompatible with thinking models because reasoning exhausts the
 *       token budget before the grammar-pinned output can emit</li>
 *   <li>when {@code endpointStyle=ollama_native}, promotes the shim backends
 *       (LM_STUDIO, OLLAMA) to {@code OLLAMA_NATIVE} and switches the path to
 *       {@code /api/chat}</li>
 * </ul>
 *
 * <p>When no profile matches the model name, the applier logs a single-line
 * note and leaves the config untouched.
 */
public final class ModelProfileApplier {

    private ModelProfileApplier() {}

    public static ModelProfile apply(LLMConfigGroup cfg) {
        String modelName = cfg.getModelName();
        Path profilesPath = Path.of(cfg.getModelProfilesPath());
        Map<String, ModelProfile> profiles = ModelProfileLoader.load(profilesPath);

        ModelProfile profile = profiles.get(modelName);
        if (profile == null) {
            System.out.println("[ModelProfileApplier] no profile found for model='" + modelName
                    + "' in " + profilesPath + " — using LLMConfigGroup defaults");
            // Fill runtime-only fields with reasonable guesses from the config.
            cfg.setReasoningModel(false);
            cfg.setThinkingTokenCap(0);
            cfg.setEnableThinking(cfg.isEnableThinking());
            return ModelProfile.defaults(modelName);
        }

        cfg.setMaxTokens(profile.maxTokens());
        cfg.setContextWindowTokens(profile.contextWindow());
        cfg.setTemperature(profile.temperature());
        cfg.setReasoningModel(profile.isReasoning());
        cfg.setThinkingTokenCap(profile.thinkingTokenCap());
        cfg.setEnableThinking(profile.enableThinking());

        // Endpoint style routing: flip the backend to OLLAMA_NATIVE when the profile
        // asks for it and the runner left the default OPENAI_COMPAT in place. The
        // default path is now resolved from the backend itself, so clearing llmPath
        // lets the switch to /api/chat happen automatically.
        if (ModelProfile.ENDPOINT_OLLAMA_NATIVE.equals(profile.endpointStyle())
                && cfg.getBackendEnum() == BackendType.OPENAI_COMPAT) {
            cfg.setBackend(BackendType.OLLAMA_NATIVE);
            if (cfg.getLlmPath() != null && cfg.getLlmPath().contains("/v1/")) {
                cfg.setLlmPath(null);
            }
            System.out.println("[ModelProfileApplier] endpoint=" + cfg.getBackendEnum()
                    + "  url=" + cfg.getFullLlmUrl());
        }

        // Grammar mode is unsafe with reasoning models: the think trace exhausts
        // the token budget before the grammar-pinned output emits.
        if (profile.isReasoning() && Boolean.getBoolean("matsim.llm.grammar")) {
            System.err.println("[ModelProfileApplier] WARNING: model '" + modelName
                    + "' is a reasoning model (isReasoning=true); disabling"
                    + " -Dmatsim.llm.grammar (incompatible with thinking).");
            System.setProperty("matsim.llm.grammar", "false");
        }

        System.out.println("[ModelProfileApplier] applied profile '" + modelName
                + "'  maxTokens=" + profile.maxTokens()
                + "  ctx=" + profile.contextWindow()
                + "  temp=" + profile.temperature()
                + "  thinking=" + profile.enableThinking()
                + "  reasoning=" + profile.isReasoning()
                + "  thinkingCap=" + profile.thinkingTokenCap());
        return profile;
    }
}
