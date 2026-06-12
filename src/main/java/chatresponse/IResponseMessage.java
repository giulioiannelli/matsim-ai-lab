package chatresponse;

import java.util.List;

import chatcommons.IChatMessage;
import tools.IToolCall;

/**
 * A message received from the model, possibly including tool calls.
 */
public interface IResponseMessage extends IChatMessage {
    List<IToolCall> getToolCalls(); // null or empty if not a tool call

    /**
     * Reasoning text when the backend returns it as a separate field on the
     * message (Ollama OpenAI-compatible endpoint, vLLM reasoning models, etc.).
     * Returns null when absent; callers should also consult
     * {@link IChatCompletionResponse#getReasoning()} which merges in-content
     * {@code <think>} blocks.
     */
    default String getReasoning() { return null; }
}

