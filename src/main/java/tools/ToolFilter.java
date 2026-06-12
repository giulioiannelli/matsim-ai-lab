package tools;

import java.util.List;
import java.util.Set;

/**
 * Decides which tools are visible to the LLM in a given round.
 *
 * This enables "staged tool exposure" — e.g., only info-gathering tools
 * in round 1, then only action tools once info has been gathered. This
 * keeps the schema load small for each round, which is critical for
 * small models that get confused by many similar tool schemas.
 *
 * The default (null filter) exposes all registered tools, matching
 * baseline behavior exactly.
 */
public interface ToolFilter {

    /**
     * Snapshot of the conversation state used to decide tool visibility.
     */
    interface ChatState {
        /** The current round number, starting at 1. */
        int round();

        /** Names of all tools called so far in this conversation, in order. */
        List<String> toolsCalledSoFar();

        /** Convenience: has any of the named tools been called? */
        default boolean anyCalled(String... names) {
            List<String> called = toolsCalledSoFar();
            for (String n : names) {
                if (called.contains(n)) return true;
            }
            return false;
        }
    }

    /**
     * Returns the set of tool names that should be visible in this round.
     * A null or empty return value means "expose all tools" (safe default).
     */
    Set<String> visibleTools(ChatState state);
}
