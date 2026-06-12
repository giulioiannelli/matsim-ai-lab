# Persona-Driven Replanning — Master Roadmap

> **Local-first, committed plan.** This is the authoritative roadmap for the
> project. It lives in-repo under `.claude/plans/` and is version-controlled so
> it cannot be washed out by global memory or lost chats. Update it here; do not
> rely on the global `~/.claude/plans/` directory.
>
> Rebuilt 2026-06-12 from `.claude/diary/` + memory after the original master
> plan (`glowing-leaping-dragon.md`, global, randomly named) was lost. Last
> actual work on the project: 2026-04-21 (Phase 4 close-out).

## North star

Build MATSim travel agents that **replan according to a persona**, and a
**suite to analyze their behavior**. Standard MATSim agents replan by rule
(best-score, re-route). Here, selected agents send their daily plan to a local
LLM that reasons in-character about mode choice, calls routing/comparison tools,
and returns a modified plan via structured tool calling.

We are deliberately constrained to **small, locally-runnable models** (qwen3.5:9b
on an RTX 3080 Laptop 16GB). The scientific bet is that good tools + good prompts
can make even a small model produce *plausible, in-character, tool-grounded*
travel decisions.

## The three things not yet realized (the actual research target)

The infrastructure (phases 0–4 below) is built. What we have **not** yet
demonstrated, and what the project is really about:

1. **Persona emergence** — the agent reasons and decides *as the assigned
   person* (first-person voice, choices consistent with attributes/constraints),
   not as a generic narrator/template-matcher.
2. **Correct tool use** — the agent calls the right tool at the right time with
   valid arguments (no hallucinated tool names, no malformed args), and *uses
   the result* rather than ignoring it.
3. **Meaningful routing strategy** — when given real options (via
   `compare_routes` / `available_modes`), the agent's final choice is grounded
   in those numbers, not arbitrary.

These three are the success criteria the roadmap drives toward. Everything below
is in service of demonstrating them with a small model.

## Architecture recap

Full detail in `CLAUDE.md` and `research/guides/03-llm-plugin-architecture/`.
One-line version: MATSim replanning phase → serialize `Plan` → `PlanDTO` (JSON)
→ LLM (system prompt = persona) → multi-turn tool calls (`router_tool`,
`available_modes`, `compare_routes`, `evaluate_plan`, `pull_additional_context`)
→ `extract_plan` ends the turn → validate → replace plan → MATSim scores.

---

## Part A — Completed infrastructure (phases 0–4)

| Phase | What | Status |
|---|---|---|
| **0** | Diagnosis & qwen3.5 baseline. Reasoning lives in `message.reasoning` (not `<think>`); strict rubric scores plans 1–15%; `reasoningTokens` counter was broken. | ✅ closed 2026-04-20 |
| **1** | Reasoning-model enablement. Reasoning-capture fix (`len(reasoning)/4` fallback when backend `usage` omits the split); model-profile scaffolding. | ✅ closed 2026-04-20 |
| **1.5** | Multi-provider chat infra + **Ollama native endpoint**. `BackendType` = `OPENAI_COMPAT` / `OLLAMA_NATIVE`; `ModelProfileApplier` auto-promotes per profile. | ✅ closed 2026-04-20 |
| **1.7** | **Persona prompt variant**. First-person voice + multi-tool use *emerged* on Run B; `num_ctx=8192` fix. Safe envelope: `ctx=8192, maxTok=4096` on RTX 3080 Laptop. | ✅ closed 2026-04-20 |
| **2** | Two-phase decoding (grammar OR reasoning). | ⏸️ **deprioritized** — rationale collapsed on the native endpoint (grammar + reasoning coexist). Keep as a fallback for stubborn models only. |
| **3** | Filesystem prompt registry / versioning. | ⏸️ **deprioritized** — Phase 4 used an in-code addendum pattern instead. |
| **4** | **Comparison toolset**: `compare_routes` + `evaluate_plan`, flag-gated (`--enable-comparison-tools`, output suffix `-cmp`). 47/47 tests; both fire on persona in smoke. `suggest_modes` dropped (overlapped `available_modes`). | ✅ closed 2026-04-21 |

In-house tools already available to the agent (beyond the upstream
`router_tool` / `pull_additional_context` / `extract_plan`):
`available_modes`, `activity_chain_summary`, `validate_timing`, plus the Phase-4
`compare_routes` / `evaluate_plan`.

---

## Part B — Current focus: Behavior Realization loop (the resume point)

**Where we are:** Phase 4 landed and committed; sims were paused per the
"don't run long simulations for now" directive. The infra is ready. The next
work is **not** new infrastructure — it is closing the loop on the three
unrealized goals above. (Persona-memory and a model bake-off are real, but they
are speedups/upgrades that are premature before base behavior is demonstrated —
see `feedback_never_regress_baseline`: correctness first, then consistency, then
speed.)

The loop (run → analyze → iterate), per `CLAUDE.md` "Tool design workflow":

1. **Define success metrics** for the three goals, with thresholds, e.g.:
   - persona emergence: % first-person reasoning; choice-vs-attribute
     consistency; (current baseline ≈ 0.40 realism score, 77% decision
     predictability — see `project_nlp_findings`).
   - correct tool use: tool-call success rate; hallucinated-tool-name rate (→0);
     arg-validation pass rate; "tool result actually consumed" rate.
   - meaningful routing: fraction of final mode choices traceable to a
     `compare_routes` / `available_modes` result.
2. **Controlled validation run** (gated, small): persona + `--enable-comparison-tools`,
   a fixed small agent set, **multiple seeds** to separate signal from
   seed-luck. Honor `feedback_offline_verify_first` (Java unit tests + curl
   probes green first) and `feedback_early_stop_simulations` (kill if <60%
   success). Name the output per `feedback_output_dir_naming`.
3. **Analyze** with the Python suite (`matsim-py-analysis`, conda env
   `matsim-ai`): `reasoning`, `tool-usage` (hallucination/anomaly detection),
   `bottlenecks`, plus the NLP decision-predictability / realism scoring.
4. **Iterate** prompts/tools against the metrics. Likely levers, cheapest first:
   - tighten persona first-person discipline ("speak as yourself, never as a
     narrator");
   - strengthen the comparison-tools addendum so options are reliably consulted
     before `extract_plan`;
   - check whether the legacy prompt regresses with the comparison addendum
     (open question from the Phase-4 diary — only persona was tested).
5. **Record** results in `.claude/diary/` and update this roadmap's status.

**Immediate next decision (was open at pause):** run the gated multi-seed
validation with the two Phase-4 bug fixes in place (quoted-`modes` parsing +
NaN-distance guard), *or* iterate prompts first. Recommendation: one short
multi-seed validation run to get a current metric baseline, then iterate — you
can't tell which lever matters without the numbers.

---

## Part C — Forward backlog (after base behavior is demonstrated)

| Item | Purpose | Notes |
|---|---|---|
| **Persona memory** (old Phase 5) | Read/write accumulated persona to Qdrant; speedup + cross-scenario transfer. | `persona_memory` (read) + `update_persona_memory` (write, dummy). Gated on base behavior working. See `project_persona_memory_vision`. |
| **Reasoning-model bake-off** (old Phase 6) | Compare `deepseek-r1-distill-qwen:7b` vs qwen3.5 on the three metrics. | Pull the model; probe via `scripts/probes/`. VRAM headroom question: 7b only vs also 14b. |
| **Test/CI authoring** (old Phase 7) | Wrap the 47 unit tests + curl probes into a CI-style script. | Partial: tests exist, no runner. |
| **Scale test** | 3 seeds × 5 agents × persona + comparison flag. | ~3h on RTX 3080 Laptop. Was pending at pause. |
| Proposed tools (from bottleneck analysis) | `validate_timing` (done), richer `evaluate_plan` per-activity dwell times (scope question). | See `CLAUDE.md` proposed-tools table. |

## Open questions carried over

- Does the comparison addendum regress the **legacy** prompt variant? (Only
  persona was tested in Phase 4.)
- Does the model reliably call `evaluate_plan` *before* `extract_plan`, or only
  when prompted? (Needs a multi-iter validation to answer.)
- Should `evaluate_plan` surface per-activity-type dwell times, or is that scope
  creep into MATSim scoring territory?

## Conventions

- **Plans & project info are local-first**: this file and future plans live in
  `.claude/plans/` in-repo and are committed. Diaries in `.claude/diary/`.
  Research/guides in `research/`. The global memory store only *points* here.
- **Plugin repo** (`matsim_llm_plugins/`, gitignored): sync against the **fork**
  (`origin` = giulioiannelli), never upstream (auzpatwary37).
- Honor the feedback memories: never regress baseline; offline-verify first;
  early-stop bad sims; no hardcoded values; minimal Java edits; no Claude/plan
  meta in source.
