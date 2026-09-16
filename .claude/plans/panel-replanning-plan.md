# Panel replanning plan — LLM as one strategy on a healthy ground

> Local-first, committed. Written 2026-09-16 after the first 3×3 run on the
> fast 27B exposed (a) a frozen-stage-activity bug (fixed) and (b) that
> full-scale Sioux Falls is gridlocked from iteration 0 (75.8k/84.1k agents
> stuck). Supersedes the "AI-only subpopulation" design for evaluation runs.
> Status: **PROPOSED — awaiting PI sign-off before code.**

## Research question for this phase

Are LLM-persona replanning decisions *behaviourally different* from
utility-maximising rule-based replanning (inertia, habit, attribute-grounded
rules), and are they physically plausible? System-level effects and
persona-memory speed-ups come after, on the same infrastructure.

## Design

1. **Healthy ground.**
   - One-off rule-based warm-up of the full-scale scenario: boosted
     ReRoute / SubtourModeChoice / TimeAllocation weights for the first
     50–100 iterations, then lowered; innovation disabled at 80 %; plans
     saved. Mobsim ≈ 13 s/iteration ⇒ ~1 h.
   - Development loop on a 10–25 % population sample with matching
     flow/storage capacity factors, warmed the same way (minutes).
   - Ground-health checkpoint (guide §4.0) reported for every run.
2. **LLM as a normal MATSim strategy, not a subpopulation.** Panel agents
   keep all rule-based strategies; the LLM strategy adds plans that compete
   on score. No agent can be left without re-route.
3. **Panel, not lottery.** A fixed cohort (~200 agents, seeded selection)
   is LLM-eligible, so the same agents are revisited and persona memory can
   accumulate. Everyone else is rule-based only (the control population).
4. **Trigger, not random.** Within the panel the LLM is queried when there
   is something to decide: executed-score drop, stuck/late experience, or
   never-reviewed plan. A per-iteration query budget (config) caps wall
   time; selection order = worst experience first.
5. **One-shot with precomputed context.** Pre-compute available modes and
   route comparisons for each of the agent's trips and put them in the
   prompt; tools stay available for the unusual case. Target: median query
   3 min → ≤ 45 s.
6. **Controls.** (A) the panel's rule-based twin on the same seed and
   warmed plans; (B) keep-only LLM (round cap 1).

## Budget arithmetic (27B, mari, today)

| queries/iteration | wall/iteration (sequential) |
|---|---|
| 10 | ~30 min |
| 25 (2–3 parallel slots) | ~30 min |
| 84 (0.1 % of 84k) | ~4 h |

⇒ experiments of 20–30 iterations × ≤ 25 queries fit in an evening.

## Work packages (in order; each ends with a registered run)

- **WP1 warm-up runner**: `RunSiouxFalls` gains `--innovation-boost`,
  `--disable-innovation-after`, `--sample` (population fraction + capacity
  factors), writes warmed plans. Runners gain `--plans-file`.
- **WP2 LLM strategy refactor**: LLM strategy registered for the default
  subpopulation with weight + `maxQueriesPerIteration` + trigger rule in
  `LLMConfigGroup`; panel selection by seed; AI-only subpopulation kept as an
  opt-in mode for legacy comparisons.
- **WP3 one-shot context**: precomputed `available_modes` +
  `compare_routes` per trip injected into the user prompt.
- **WP4 evaluation**: ground-health + checkpoints 1–7 automated in
  `matsim-analyze` (`ground-health` command; per-agent decision table across
  iterations); control A/B scripts.

## Open questions for the PI

- Panel size and trigger thresholds (score-drop quantile?).
- Sample fraction for the dev loop (10 % vs 25 %).
- Whether the archetype-transfer idea (one decision applied to a cluster)
  belongs in this phase or the next.
