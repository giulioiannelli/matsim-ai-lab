# Persona Emergence Campaign

> **Phase plan of record** (started 2026-07-23). Successor to the constraint set
> of `.claude/plans/persona-replanning-roadmap.md` Part B: the "small local
> models only" limitation is lifted — LLM inference now runs on a remote GPU
> server (qwen3.6:27b via SSH tunnel, see diary 2026-07-23). This folder holds
> the campaign plan, the lab log, and the run registry. Session-level work logs
> stay in `.claude/diary/`; this folder tracks the **science**.

## Objectives (fixed 2026-07-23, interview with PI)

Two co-equal core objectives:

1. **Optimality in time** — the simulation is far too slow (~1.5–4 min per
   agent per iteration on the 27B). Target: **≥2× median per-agent replanning
   time reduction** at equal-or-better behavioral metrics, via the four levers
   below (in order of increasing cost).
2. **Plausibility & persona emergence** — agents that reason and decide *as
   the assigned person*, with personalities **created from initial conditions**
   (travel diaries / person attributes) and **evolved through interaction with
   the mobsim across iterations** — not re-derived from scratch every cycle.

Decisions locked by interview:

| Question | Decision |
|---|---|
| Scenario | **Sioux Falls** until objectives demonstrated; richer scenario only as validation afterwards |
| Finish line | The 3 roadmap goals with **preregistered thresholds** (below) |
| Speed levers | All four, sequenced: reasoning budget → parallelism → persona memory → two-tier |
| Memory scope this phase | **Within-run only** (across iterations; cross-run/cross-scenario = later phases) |
| Concert / off-equilibrium | **North star only** — design memory & tooling to stay compatible; no within-day work this phase |
| Publication | **Yes — paper intended.** Fixed seeds, control groups, preregistered metrics, full run registry |
| Affect analysis | Sentiment + decision tagging first (extend existing `nlp/` modules); LLM-as-judge & longitudinal affect later |
| Compute | mari: free rein incl. long batches; GPUs evicted after every run (`scripts/ollama-gpu.sh evict`) |

## Preregistered metrics & thresholds (v1 — may be revised only BEFORE M1 runs)

Measured by `matsim-py-analysis` on multi-seed runs (≥3 seeds), reported as
mean ± range across seeds:

| Goal | Metric | Threshold |
|---|---|---|
| Persona emergence | % conversations with first-person voice AND ≥1 attribute-grounded justification (`matsim-analyze persona`) | ≥ 80% |
| Persona emergence | persona↔tool contradictions | < 5% |
| Correct tool use | hallucinated tool names | 0 |
| Correct tool use | malformed/failed tool args (parsing+verification failures / total calls) | < 2% |
| Correct tool use | tool-result-consumed rate (result referenced in subsequent reasoning) | ≥ 90% |
| Grounded routing | mode changes preceded by an `available_modes`/`compare_routes` call that includes the chosen mode | ≥ 70% |
| Reliability | per-agent success rate (`planApplied` or deliberate keep) | ≥ 90%, and never below the previous baseline (feedback: never-regress) |
| Speed (M3 exit) | median per-agent replanning wall time vs M0 baseline | ≤ 0.5× |

Prior reference points (local qwen3.5:9b era): realism 0.40, decision
predictability 77% (`project_nlp_findings`); first 27B run 2026-07-23: 1/2
agents applied a plan with the round cap deliberately strangled at 4.

## Workstreams

### WS-0 — Rebaseline on the 27B (immediate)

Everything downstream compares against this.

- [ ] **Fix Qdrant cross-run contamination** — experience docs persist in the
  fixed collection `matsim_siouxfalls_llm` across runs (cleanup only wired in
  controllerlistener mode). Fix: per-run collection name (derive from output
  dir name via config; no hardcoding) or working wipe-on-start in replanning
  mode. *Blocker for any multi-seed claim.*
- [ ] **Prompt fix: activity types** — 27B run showed the model believing
  `secondary` is not a valid activity type (planned to rewrite it to `other`).
  State in the system prompt that activity types are scenario-defined and must
  be preserved verbatim.
- [ ] Rebaseline runs: 3 seeds × 5 agents × 10 iterations, persona variant,
  `--enable-comparison-tools`, `--max-tool-iterations=10`, qwen3.6:27b.
- [ ] Full metric readout (table above) + timing decomposition
  (`matsim-analyze bottlenecks` + per-round stats): where do the seconds go —
  thinking, tool rounds, prompt eval, HTTP?
- [ ] Control groups for the paper: (a) same agents under classical replanning
  (no LLM); (b) legacy prompt variant, same seeds.

### WS-A — Speed (objective 1)

Ordered by cost; each step gates on "metrics not regressed" (multi-seed).

- **A1 Reasoning budget control** (cheap, first): token/timing breakdown from
  WS-0; staged tool exposure (`MATSIM_LLM_TOOL_FILTER=staged` already exists —
  evaluate); thinking-cap sweep; prompt slimming; skip-if-satisfied (agent
  whose plan was applied unchanged N times gets a cheaper "confirm or flag"
  prompt).
- **A2 Parallel agent querying**: `finishReplanning()` is a sequential
  `forEach` of blocking HTTP calls — parallelize across agents with a bounded
  executor (config: `maxConcurrentAgents`, default 1 = current behavior);
  requires `OLLAMA_NUM_PARALLEL` on mari and a VRAM check (parallel KV caches
  at ctx 12288 will spill — measure throughput vs parallelism 1/2/3).
- **A3 Persona memory, within-run** (also serves WS-B): implement the two
  missing tools from the proposed-tools table:
  - `persona_memory` (real): read the agent's distilled persona + stable
    decisions from the store;
  - `update_persona_memory` (dummy): after planning, write back a compact
    persona digest (traits observed, preferences confirmed, decisions that
    proved stable).
  Inject the digest into the system prompt (bounded token budget) so later
  iterations *start from who the agent is* instead of re-deriving it.
  Storage: Qdrant alongside experience docs (structured payload), keyed
  personId + kind=persona. Measure: tokens/iteration ↓, time/iteration ↓,
  persona metrics ≥ baseline, reasoning-repetition rate ↓
  (persona.py loop detector).
- **A4 Two-tier models** (synthesis): 27B builds/updates the persona
  (iteration 0 and on "surprise" events); a smaller model (candidates:
  qwen3.5:9b local or on mari; NOT 0.8b) executes routine per-iteration
  decisions with the persona digest. Gate: full metric table ≥ single-27B
  baseline.

### WS-B — Persona emergence & plausibility (objective 2)

- **B1 Metric hardening**: extend `analysis/persona.py` with per-agent
  **longitudinal** tracking (persona signal per iteration — does character
  stabilize?); wire the grounded-routing metric (does the final choice cite
  tool numbers?).
- **B2 Prompt iteration loop** (roadmap Part B, unchanged): run → analyze →
  adjust persona prompt discipline → rerun. Now cheap to iterate: 27B follows
  instructions far better than the 9b did.
- **B3 Persona formation from initial conditions**: personality =
  attributes (age, sex, employment, licence, vehicle) + travel diary (the
  initial plan) + accumulated mobsim experience (congestion suffered, waits,
  successes) distilled by A3's write-back. The scientific claim to test:
  **persona digests make behavior MORE consistent and MORE plausible over
  iterations, not just faster.**

### WS-C — Analysis suite breadth (supports both)

- **C1 Sentiment & decision tagging** (first): extend existing
  `nlp/sentiment.py` + `classify` + `decisions` to per-iteration trajectories
  (agent mood over the run) and integrate into the standard readout.
- **C2 Cross-run/per-agent convergence module** (gap): track one persona's
  signals across iterations and across seeds; nothing does this today.
- **C3 Reporting**: implement the stubbed `matsim-analyze report` as the
  one-command, paper-grade readout of the full metric table for a run set;
  keep the run registry (`runs.md`) current.

## Milestones

| # | Gate | Evidence |
|---|---|---|
| M0 | Rebaseline done | Metric table + timing decomposition on 3 seeds; contamination fixed |
| M1 | Cheap speed banked | A1 (+A2 if ready) ≥1.5× faster, no metric regression |
| M2 | Persona thresholds hit | Full table green on 3 seeds with persona variant |
| M3 | Memory speedup shown | A3 in: ≥2× vs M0 total, repetition rate down, persona metrics ≥ M2 |
| M4 | Paper assembly | Controls + registry + figures; two-tier (A4) if it made the cut; concert vision as outlook |

## Rigor rules (paper mode)

- Every run: distinct `--seed`, self-identifying output dir name, entry in
  `runs.md` with exact CLI + git SHA + model tag.
- No threshold edits after M1 runs begin (preregistration discipline).
- Offline-verify-first and early-stop rules stay in force
  (`feedback_offline_verify_first`, `feedback_early_stop_simulations`).
- GPUs on mari evicted after every run (`scripts/ollama-gpu.sh evict`).

## Folder map

- `README.md` — this plan (update statuses in place).
- `lab-log.md` — dated scientific log: what was run, what was learned.
- `runs.md` — run registry (one row per simulation, no exceptions).
- future: `scenarios/` notes, figures, paper drafts.
