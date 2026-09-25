package matsimBinding.oneshot;

import tools.ToolFilter;

import java.util.Set;

/**
 * Tool set for one-shot replanning: the information tools have already been
 * run and their results sit in the prompt, so only the action tools are
 * advertised. Fewer schemas per round also shrinks every request: the full
 * plan schema (~5k characters) is carried by extract_plan, evaluate_plan and
 * validate_timing alike, so only extract_plan keeps it here; timing is
 * checked by the extraction validator anyway.
 */
public final class OneShotToolFilter implements ToolFilter {

    public static final Set<String> VISIBLE = Set.of("extract_plan", "router_tool");
    public static final Set<String> VISIBLE_DECISION = Set.of("decide_trips", "router_tool");
    /** Compact context: the route options are in the prompt, only the decision is left to make. */
    public static final Set<String> VISIBLE_DECISION_ONLY = Set.of("decide_trips");

    private final Set<String> visible;

    public OneShotToolFilter() { this(false); }

    /** @param decisionOutput advertise decide_trips instead of extract_plan */
    public OneShotToolFilter(boolean decisionOutput) { this(decisionOutput, false); }

    /** @param decisionOnly advertise decide_trips alone (compact context) */
    public OneShotToolFilter(boolean decisionOutput, boolean decisionOnly) {
        this.visible = decisionOutput ? (decisionOnly ? VISIBLE_DECISION_ONLY : VISIBLE_DECISION) : VISIBLE;
    }

    @Override
    public Set<String> visibleTools(ChatState state) {
        return visible;
    }
}
