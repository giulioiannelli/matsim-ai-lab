# Tool Registry — Provenance & Status

This file tracks which tools exist, who created them, and their current status. Use this to trace bugs: if a tool was added in-house, check the implementation first; if it came from upstream, check if the upstream version has fixes.

## Provenance key

| Origin | Meaning |
|--------|---------|
| **upstream** | Vendored from `auzpatwary37/matsim_llm_plugins` (May2025 branch) |
| **in-house** | Developed in this repo (matsim-ai-lab) |

## Tool inventory

| Tool name | Class | Origin | Type | Purpose | Status |
|-----------|-------|--------|------|---------|--------|
| `extract_plan` | `ExtractPlanTool.java` | upstream | dummy | Extracts final plan from LLM, ends conversation | Stable |
| `router_tool` | `RouterTool.java` | upstream | real | Calls MATSim TripRouter between two facilities | Stable |
| `pull_additional_context` | `PullAdditionalContextTool.java` | upstream | real | Queries Qdrant RAG for past experiences | Stable |
| `available_modes` | `AvailableModesTool.java` | in-house | real | Pre-computes which modes are available at each location | New (2026-04-14) |
| `activity_chain_summary` | `ActivityChainSummaryTool.java` | in-house | real | Simplified plan view without PT chain noise | New (2026-04-14) |
| `validate_timing` | `ValidateTimingTool.java` | in-house | real | Checks temporal consistency before extract_plan | New (2026-04-14) |
| `compare_routes` | `Implement/comparison/CompareRoutesTool.java` | in-house | real | Routes the same OD pair under several modes; returns a side-by-side metrics table | New (2026-04-21), flag-gated |
| `evaluate_plan` | `Implement/comparison/EvaluatePlanTool.java` | in-house | real | Computes observable plan metrics + structural warnings (no score) | New (2026-04-21), flag-gated |

## Upstream tools — do not modify without syncing

These three tools were copied from `matsim_llm_plugins` on 2026-04-13 (commit `bebc622`). If you modify them, note the change here so we can reconcile with future upstream pulls.

**Files:**
- `src/main/java/tools/Implement/ExtractPlanTool.java`
- `src/main/java/tools/Implement/RouterTool.java`
- `src/main/java/tools/Implement/PullAdditionalContextTool.java`

**Upstream equivalent:**
- `matsim_llm_plugins/matsim_llm_plugins/src/main/java/tools/Implement/`

## In-house tools — our additions

These tools were created in matsim-ai-lab to address bottlenecks found during initial simulation runs (see diary 2026-04-14). They are NOT in the upstream repo.

**Files:**
- `src/main/java/tools/Implement/AvailableModesTool.java`
- `src/main/java/tools/Implement/ActivityChainSummaryTool.java`
- `src/main/java/tools/Implement/ValidateTimingTool.java`
- `src/main/java/tools/Implement/comparison/CompareRoutesTool.java` (flag-gated: `comparisonToolsEnabled` / `--enable-comparison-tools`)
- `src/main/java/tools/Implement/comparison/EvaluatePlanTool.java` (flag-gated: same)

## When adding a new tool

1. Add a row to the table above with origin = `in-house` and the date
2. Register in `LLMIntegrationModule.install()`: `toolManager.registerTool(new MyTool())`
3. Update the system prompt in `IndividualPrompt.java` if the LLM needs to know about it
4. Test: run simulation and verify tool appears in `llm_chat_log_ChatLog_combined.jsonl`
5. Document in `research/guides/03-llm-plugin-architecture/tool-framework.md` if it's complex

## Debugging guide

| Symptom | Check first |
|---------|------------|
| `No tool registered with name: X` | LLM hallucinated the name. Check prompt, try different model |
| Tool returns wrong route | Is `RouterTool` getting correct facilityIds? Check PlanDTO serialization |
| RAG returns irrelevant docs | Check Qdrant collection, embedding model, metadata filters |
| New in-house tool crashes | Check `verifyArguments()`, context object availability, null handling |
| Tool works locally but not after upstream pull | Diff the upstream version against ours — `ITool` interface may have changed |
