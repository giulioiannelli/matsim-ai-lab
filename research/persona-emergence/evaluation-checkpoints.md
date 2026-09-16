# Evaluating LLM replanning — pipeline panorama, checkpoints, ground truth

How one iteration runs with the LLM module, where each artefact lands, what
counts as "good work", and how to catch illegal or silly agent behaviour.
Companion to the campaign plan (README.md) and its preregistered metrics.

---

## 1. Panorama: what happens in one iteration

```
it.N  mobsim (QSim)  ──►  scoring  ──►  replanning  ──►  it.N+1
                                          │
      "default" subpopulation  ──────────►│ rule-based strategies (re-route,
      (everyone except the AI agents)     │ time mutation, ExpBeta selection)
                                          │
      "llm" subpopulation ───────────────►│ LLMReplanningStrategyModule
      (--num-agents, fixed per run)       │   for each AI agent (sequential):
                                          │     1. Plan → PlanDTO JSON
                                          │     2. persona system prompt + plan
                                          │     3. multi-turn tool loop on Ollama:
                                          │        activity_chain_summary,
                                          │        available_modes, router_tool,
                                          │        compare_routes, evaluate_plan,
                                          │        validate_timing,
                                          │        pull_additional_context (Qdrant)
                                          │        … until extract_plan (or cap)
                                          │     4. Java validator (see §4)
                                          │     5. plan replaced, or kept on failure
                                          ▼
      MATSim scores the executed plan next iteration; ExpBeta keeps/discards it
      against the agent's other plans. The LLM never sees the score directly —
      it sees its experience via the Qdrant memory (AgentExperienceEventHandler).
```

Timing per agent (27B, ctx 12288, full GPU, local embedder): ≈ 30 s per tool
round, 3–12 rounds ⇒ 1.5–6 min. Iteration wall ≈ agents × that, plus ≈ 1 min
mobsim for Sioux Falls.

## 2. Where everything lands (`output/<run>/`)

| Artefact | File | Use |
|---|---|---|
| Full LLM conversations | `llm_chat_log_ChatLog_combined.jsonl` (+ `ITERS/it.N/N.llm_chat_log_ChatLog.jsonl`) | reasoning traces, tool calls, timing, tokens |
| Per-agent LLM stats | `llm_person_stats_combined.csv` | success, rounds, failures by type, `planApplied`, `hitMaxIterations`, tokens, duration |
| Scores, split by subpopulation | `scorestats_llm.csv` vs `scorestats_default.csv` (+ `scorestats.csv`, `.png`) | **the primary ground truth**: did LLM agents' executed score rise like the rule-based agents' did? |
| Mode shares | `modestats.csv`, `pkm_modestats.csv`, `ph_modestats.csv` (+ pngs) | population-level plausibility |
| Trips / legs / activities | `output_trips.csv.gz`, `output_legs.csv.gz`, `output_activities.csv.gz`; per-iteration `ITERS/it.N/N.trips.csv.gz` | per-agent before/after (mode, distance, times) |
| Plans | `output_plans.xml.gz`, `ITERS/it.N/N.plans.xml.gz` | exact plan diffs |
| Events | `ITERS/it.N/N.events.xml.gz` | what physically happened (stuck, teleport) |
| Travel-time / distance stats | `traveldistancestats.csv`, `ITERS/it.N/N.legHistogram*.png` | congestion & departure profile |
| Dashboards | `dashboard-*.yaml`, `simwrapper-config.yaml` | `simwrapper here` in the run dir |

## 3. Visualisation

- **SimWrapper** (`simwrapper here` inside the run dir, or drop the folder on
  https://simwrapper.app): mode share, score convergence, trip tables, a map
  of the network with link volumes, per-iteration leg histograms. Best for
  population-scale sanity.
- **Python suite** (`conda run -n matsim-ai matsim-analyze …`):
  `analyze` (mode share first→last, score convergence, LLM success),
  `persona` (emergence, loops, contradictions), `tool-usage` (call
  patterns, hallucinated names, argument anomalies), `chat` (readable
  transcripts), `reasoning` / `classify` / `sentiment` (NLP on traces),
  `compare A B` (two runs), `report` (HTML).
- **Via** (desktop) for animated agent trajectories from `events.xml.gz` —
  the fastest way to *see* an agent teleporting or stuck.

## 4. Checkpoints, in the order they can fail

0. **Ground health (before judging anyone)** — count `stuckAndAbort` events
   and compare departures vs arrivals by mode in `ITERS/it.N/N.events.xml.gz`.
   If most trips never arrive, scores of all agents are dominated by the jam
   and no replanning comparison is meaningful. Full-scale Sioux Falls at it.0:
   15.6k of 83.0k car departures arrive, 75.8k of 84.1k agents stuck at the
   30:00 end (2026-09-16). Warm the scenario up or sample it down first.

1. **Wire & protocol** — `llm_person_stats`: `toolParsingFailures`,
   `toolVerificationFailures`, `toolExecutionFailures`, `noToolCallRetries`
   all ≈ 0; `tool-usage` reports 0 hallucinated tool names. Preregistered:
   malformed args < 2 %, hallucinated names = 0.
2. **Legality** — before any plan is applied, the Java validator
   (`checkOriginalActivityConsistency`) rejects a plan that changes the
   *number, order, type or location* of the original activities. The LLM may
   only change modes, routes and times. `validate_timing` offers the same
   checks (plus activity/leg alternation and timing consistency) to the model
   before it submits. Rejections show up as `success=false` with
   `failureType`. A plan that passes is legal by construction *for the
   activity chain*; mode legality is the next check.
3. **Mode feasibility** — an agent without a car/licence must not drive; a
   bike trip needs a bike. `available_modes` is the oracle; the preregistered
   "grounded routing" metric asks that ≥ 70 % of mode changes were preceded
   by an `available_modes` or `compare_routes` call that contains the chosen
   mode. `persona` flags persona↔tool contradictions (says "I have no car",
   picks car).
4. **Physical plausibility of the executed day** — from `output_trips`/`legs`
   and events: no trip faster than the mode's speed envelope, no walk > ~5 km
   at commute time, no activity ending before the previous trip arrives, no
   stuck/abort events for AI agents. Cheap query: join `N.trips.csv.gz` on
   `person` for the `llm` subpopulation and compare `trav_time`,
   `traveled_distance`, `main_mode` against iteration 0. **Worked example
   (2026-09-16)**: two agents switched to pt, score rose from −40 to +5 — but
   events showed one departure and one `pt interaction` start each, then
   nothing: the round-tripped stage activity had no maximum duration and never
   ended. Score alone would have called it an improvement.
5. **Reliability** — `planApplied` or a *deliberate keep* (model validated
   and returned the original) ≥ 90 % per agent; never below the previous
   baseline (never-regress rule). `hitMaxIterations` > 0 means the round cap
   truncated productive work (raise cap) or a loop (see 6).
6. **Reasoning quality / persona** — `persona`: first-person voice +
   attribute-grounded justification in ≥ 80 % of conversations; no degenerate
   repetition loops (same tool, same args, ≥ 3×).
7. **Does replanning improve anything?** — the research question, three
   lenses:
   - *Agent lens*: `scorestats_llm.csv` executed score should not fall
     relative to iteration 0 and, over iterations, should approach the
     `default` subpopulation's improvement. A control is essential (§5).
   - *Decision lens*: per-agent mode/time change table across iterations —
     do choices stabilise (a persona remembering) or oscillate (no memory)?
   - *System lens*: `modestats`, link volumes — with few AI agents this is
     flat by construction; only meaningful at scale.

## 5. Ground truth and controls

- **Control A — rule-based twin**: same seed, same N agents left in the
  `default` subpopulation (i.e. `--num-agents=0`, or the baseline runner).
  Their `scorestats` trajectory is the yardstick for "improvement".
- **Control B — keep-only LLM**: an LLM run where the prompt forbids
  changes (or `--max-tool-iterations=1`) isolates the cost of *being* an AI
  agent from the value of its decisions.
- **Deliberate keep ≠ failure**: an agent that validated and kept its plan
  made a decision. Count it as success; the naive "plan changed" rate
  under-reports.
- **Seeds**: MATSim `--seed` fixes the mobsim/replanning randomness; the LLM
  at temperature 0.3 is *not* seeded. Multi-seed runs (≥ 3) are the only way
  to separate signal from LLM sampling noise. Report mean ± range.
- **Mari is shared**: other users' jobs add ~20 s per round at random —
  compare *tokens* and *rounds*, not wall time, across runs on different days.

## 6. A minimal evaluation routine after a run

```bash
D=output/<run-dir>
conda run -n matsim-ai matsim-analyze analyze    $D   # mode share, scores, LLM success
conda run -n matsim-ai matsim-analyze tool-usage $D   # hallucinations, anomalies
conda run -n matsim-ai matsim-analyze persona    $D   # emergence, loops, contradictions
conda run -n matsim-ai matsim-analyze chat       $D   # read 2–3 transcripts by hand
simwrapper here                                       # from inside $D
```
Then register the run in `runs.md` with one line of outcome.
