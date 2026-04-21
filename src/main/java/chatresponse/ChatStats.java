package chatresponse;

public class ChatStats {
    public int llmRounds;
    public int totalToolCalls;

    public int toolParsingFailures;
    public int toolVerificationFailures;
    public int toolExecutionFailures;
    public int externalValidationFailures;
    
    public int noToolCallRetries;
    public boolean hitMaxIterations;

    public long durationMs;

    public boolean success;
    public String failureType;

    public int promptTokens;
    public int completionTokens;
    public int reasoningTokens;
    public int totalTokens;

    /**
     * Number of rounds whose reasoning-token count exceeded the per-model
     * {@code thinkingTokenCap} from the active model profile. Zero when no
     * cap is configured or when reasoning stays under budget every round.
     * Populated in {@code DefaultChatManager}; advisory only — no truncation
     * is performed at the request level.
     */
    public int thinkingTokenCapHit;

    /**
     * Count of tool calls to the comparison toolset ({@code compare_routes},
     * {@code evaluate_plan}) within this agent's replan. Zero when the toolset
     * is disabled or the model never invokes one. Measures whether comparison
     * tools actually get used once they are advertised.
     */
    public int comparisonToolInvocations;
}