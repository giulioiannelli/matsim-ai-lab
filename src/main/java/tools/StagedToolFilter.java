package tools;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Two-stage tool filter designed for small models that get confused by
 * large tool schemas. Exposes info-gathering tools until one has been
 * called, then switches to action tools (routing, validation, extraction).
 *
 * Rationale: Small models (e.g., qwen3.5 9B) show a strong tendency to
 * hallucinate tool names when presented with many similar schemas. By
 * reducing the schema set to 3-4 tools per round, we keep the model's
 * attention focused.
 *
 * Design:
 *   - Stage INFO (before any info tool is called):
 *       activity_chain_summary, available_modes, pull_additional_context,
 *       router_tool, extract_plan
 *   - Stage ACTION (after any info tool is called):
 *       router_tool, validate_timing, extract_plan
 *
 * router_tool and extract_plan are always visible so the model can always
 * route a trip or finalize its plan.
 */
public class StagedToolFilter implements ToolFilter {

    public static final String ACTIVITY_CHAIN_SUMMARY = "activity_chain_summary";
    public static final String AVAILABLE_MODES = "available_modes";
    public static final String PULL_ADDITIONAL_CONTEXT = "pull_additional_context";
    public static final String ROUTER_TOOL = "router_tool";
    public static final String VALIDATE_TIMING = "validate_timing";
    public static final String EXTRACT_PLAN = "extract_plan";

    /** Tools that count as "info-gathering" — calling any triggers stage transition. */
    private static final Set<String> INFO_TOOLS = Set.of(
        ACTIVITY_CHAIN_SUMMARY,
        AVAILABLE_MODES,
        PULL_ADDITIONAL_CONTEXT
    );

    @Override
    public Set<String> visibleTools(ChatState state) {
        boolean anyInfoCalled = false;
        for (String called : state.toolsCalledSoFar()) {
            if (INFO_TOOLS.contains(called)) {
                anyInfoCalled = true;
                break;
            }
        }

        Set<String> visible = new HashSet<>();

        if (!anyInfoCalled) {
            // INFO stage: expose info tools plus always-available action tools
            visible.add(ACTIVITY_CHAIN_SUMMARY);
            visible.add(AVAILABLE_MODES);
            visible.add(PULL_ADDITIONAL_CONTEXT);
            visible.add(ROUTER_TOOL);
            visible.add(EXTRACT_PLAN);
        } else {
            // ACTION stage: hide info tools to prevent loops, expose action tools
            visible.add(ROUTER_TOOL);
            visible.add(VALIDATE_TIMING);
            visible.add(EXTRACT_PLAN);
        }

        return visible;
    }
}
