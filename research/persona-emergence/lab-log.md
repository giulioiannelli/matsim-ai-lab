# Lab log — persona emergence campaign

Newest entries on top. One entry per working session that produced or analyzed
data. Session-level operational logs stay in `.claude/diary/`.

---

## 2026-09-23 — Campaign run 1 stopped after two iterations: the context block forbade the switch

**Setup**: first campaign run (panel 200, budget 10, 25 iterations, brief
decision output, seed 4721) launched 15:37 as a systemd user unit with the
new per-iteration ETA reporter (`progress.txt`, commit 13a31dd).

**What the first two iterations showed** (registered in `runs.md`):
- 19 of 20 agents locked their day unchanged; the one change was "car out,
  pt back", i.e. the car left at the destination.
- The reasoning traces explain it. The one-shot block listed "modes you can
  use when leaving each place" computed from the *current* plan: a car owner
  commuting by pt has the car at home, so the block said "from work: walk,
  pt, car_passenger" and the return trip had no car option. Combined with
  the ground rule "the car is wherever you last parked it", the 27B concluded
  it could not drive home and therefore did not drive out either — three
  car owners on 2–4-transfer pt commutes talked themselves out of the switch
  explicitly. The panel's target population (car owners stuck on pt) was
  exactly the one the context made immobile.
- Persona quality itself was fine: every trace first person, age and job
  cited, transfers and walking distance weighed sensibly, 0 malformed calls.
- Speed: 18 min then 11 min per iteration (ETA 21:41, not 18:10). Every
  one of 22 rounds reloaded the model (581 s, a third of LLM time): sampling
  `/api/ps` every 5 s caught a colleague running the same 27B at ctx 4096
  between our calls (a different context size forces a reload). One agent
  (76-year-old, no car) ran its reasoning to the 6144-token cap in a
  "Wait, …" re-checking loop after having decided, 448 s over two rounds;
  the brief-style addendum did not prevent it.

**Fix (this session)**: tour-level vehicle availability. The block now says
which vehicles the person has and where each is parked at the start of the
day, states the rule (a vehicle moves with you; driving out and back is
fine, pt out and driving back is not), and lists the car/bike route option
for every trip reachable by an unbroken chain, with `"requires":"trip 1 as
well"` on the conditional ones. `decide_trips` verification now follows the
same chain with the decided modes applied over the current ones (drive out
+ drive back accepted; drive back alone rejected with a message that says
what to do). `--max-tokens` added as a hard per-round cap (3072 for brief).
Tests: 94 Java tests pass.

**Campaign run 2 (16:30–19:36, registered in `runs.md`)**: the first complete
campaign run. Protocol is now clean at scale: 250/250 decisions applied in
one round each, no retries, no verification failures, no cap hits, 39 s
median per agent (25 % of LLM time still colleague-induced reloads). Persona
emergence 78 % on brief reasoning, no loops or contradictions. The panel
logic behaved as designed: 200 first reviews, then the score-drop trigger
took over from iteration 21 (39 agents seen twice or more). The ground did
not move (executed score 20.16 → 20.17, mode shares within a point).

The substantive result is the scoring verdict. The 27B changed 59 of 250
days (42 distinct agents), almost always pt → car out and back (102 car legs,
4 pt, 7 walk, no departure shifts), citing transfers and age. At iteration
25 the LLM-decided modes sit in the selected plan for only 16 of the 42
changed agents (best-scoring plan: 17/42): under capacity factor 0.1 the car
option is often slower than pt and MATSim's scoring sends most of these
agents back to pt. This is the "MATSim has the final word" mode working —
persona preference proposes, the utility function disposes — and it is the
first quantitative measure of how often the two disagree. Open questions for
WP4: does the LLM re-propose car when re-selected by score drop (39 repeats
to inspect), and how does the 16/42 survival compare with a rule-based
control on the same panel.

Housekeeping: `scripts/ollama-gpu.sh evict` now leaves alone an instance of
our model name loaded at a different context size (the colleague's ctx-4096
27B shares the name `qwen3.6:27b`); `status` prints the context length.

## 2026-09-18 — Speed programme: steps 1–2 confirmed, one-shot built, a tool bug found

**Where the time went** (panel smoke, 12 agents, 79 rounds, 4,410 s of LLM
wall): model load 35 % (~19.5 s reload on every round), prompt evaluation
14 % (7.5k tokens re-read per round), generation 50 % (730 tokens/round at
26 tok/s, ~90 % thinking). Recorded with the enlarged WP3 plan.

**Step 1 (keep-alive) + step 2 (prefix cache) work.** `keepAlive: 10m` is
sent; `scripts/probes/keepalive_probe.sh` shows the second of two requests
loading in 0.7 s and the model held for 10 min. In the running 50-agent
panel run the first ~6 rounds still reloaded every time (11–33 s each; a
colleague's GPU job started at the same minute and the two workloads were
evicting each other), then from 14:22 on: load 0.8 s, and the *second round
of a conversation evaluates 7,000 prompt tokens in 1.2 s* (first round
7.2 s) — Ollama reuses the cached prefix. Rounds went from 25–165 s to
10–27 s. Lesson for the shared server: our speed depends on nobody else
alternating models with us; worth a word with the colleague about time
windows.

**mari serialises requests**: the tiny probe waited 100 s behind the
simulation's request, so the server runs one request at a time
(`OLLAMA_NUM_PARALLEL` = 1, not ours to change). Step 7 (concurrency) is
therefore off the table on mari; parallelism would only queue.

**Step 3 (one-shot context) built**: `matsimBinding.oneshot` runs
activity_chain_summary, available_modes (per place) and compare_routes (per
trip, only the modes available there) programmatically and writes a compact
"what you already know" block into the first user message; the system
prompt's tool guidance switches to "decide, don't look up"; only
`extract_plan` and `router_tool` are advertised (tool schemas 21k → ~6.7k
chars, step 5 in passing). Legs whose mode changes may be returned without
a route: the converter accepts it and MATSim routes them before the mobsim.
Flag `--one-shot`; output dir tag `-oneshot`. First render checked on a
local 9B run (`--llm-port` added so the laptop's Ollama can serve dev
smokes without touching mari).

**27B decision-output smoke (same 12 agents, same seed as the baseline)**:
12/12 applied, one round per agent (one agent needed three), **66 s median
per agent vs 374 s** — 5.7× — with 23 % of the remaining time still model
reloads (a colleague's llama3 alternated with us; without that ≈ 50 s).
Prompt 2.7k tokens (was 7.3k), output ~1.1k tokens, almost all thinking:
generation is now 72 % of the wall, which is what the reasoning sweep
targets. Decisions look like the persona reasoning of the tool-path runs
(7 keep, 5 mode changes), to be checked with the persona metrics.

**Reasoning sweep (27B, decision output, 9 queries each)**: free 66 s
median (12/12), brief 53 s (9/9), no-thinking 46 s (9/9). In all three,
a third or more of the time is still model reloads caused by a colleague's
model alternating with ours; with the server to ourselves the same runs
project to ≈ 50 / 35 / 27 s. Decision: **brief is the campaign default** —
it keeps a reasoning trace (the persona metrics need one; no-thinking has
none) at a fifth of the baseline cost; free stays as the persona-quality
reference run. The 25-iteration × 10-query campaign now costs ≈ 2.5 h
instead of 26 h. Speed gate met; next: WP4 evaluation automation, then the
first campaign run — from mari, so the laptop can close (plan step 8).

**Hazard found by the local one-shot smoke (extract_plan path)**: the 9B
returned a walk leg with a route stub (`{"routeType":"generic","distance":…}`,
no links). The converter attached it, MATSim's parallel plans writer threw a
NullPointerException in a worker thread and the main thread waited on it
forever — a silent hang, not a crash. Same plan: the car leg's link list was
*invented* by the model. Fix: `LegDTO` drops routes without both end links
(MATSim routes the leg before the mobsim); regression test. The
`decide_trips` path cannot produce either problem, which is the strongest
argument for decision output beyond speed.

**Tool bug found by reading the one-shot block**: `available_modes` reported
"car is at home, not at work" for an agent who had *driven* to work. The
tool returned the car's end-of-day location instead of its location while
the person is at the queried place. Every past run's agents were told, at
their workplace, that their car was elsewhere. Fixed (`findVehicleLocation`
now follows the plan up to the first visit of the queried facility) with
regression tests; affects tool-path and one-shot alike from the next run.

## 2026-09-16 (night) — Panel plan accepted; WP1 warm-up + WP2 panel strategy built

**Decision (PI)**: "go with the defaults" on the panel plan
(`.claude/plans/panel-replanning-plan.md`): panel 200, trigger = worst 20 %
executed-score drop (+ stuck, + never reviewed), dev sample 10 %, archetype
transfer deferred.

**WP1 — healthy ground (done, run registered)**. `RunSiouxFalls` is now a
picocli warm-up producer (`--innovation-boost`, `--boost-until`,
`--disable-innovation-after`); `org.matsim.project.ground.GroundOptions`
(`--plans-file`, `--sample`, `--capacity-factor`) is shared with the LLM
runner. First warm-up `siouxfalls-s0.10-b10-seed4711`: 8,460 agents, 100
iterations, 7.5 min wall. The 10 % sample is *not* gridlocked even at it.0
(12 stuck legs of 27.7k departures) — the gridlock is a full-scale
phenomenon. Executed score 18.76 → 20.16 with a plateau once the boost ends
at it.50; 0 stuck legs at it.100. Mode shares moved car/pt/walk
78/19/3 % → 61/28/11 %: the boosted SubtourModeChoice finds a strongly more
pt/walk-heavy equilibrium than the initial diaries. Worth remembering when
we compare LLM decisions to "the population": the rule-based ground already
shifted a lot.

**WP2 — LLM as a normal strategy on a panel (code done, smoke running)**.
New package `matsimBinding.panel`: `PanelSelection` (pure rule: stuck →
never-reviewed → worst score drop within the quantile, then the budget),
`PanelStrategyChooser` (replaces MATSim's weighted chooser; forces the LLM
strategy on the chosen agents, lottery for everyone else; writes
`llm_panel_selection.csv` with the reason per query), `PanelExperienceTracker`
(stuck events), `PanelModule`. Runner flags: `--panel --max-queries
--trigger-quantile`. Panel agents stay in the default subpopulation with all
rule-based strategies; the LLM strategy is registered with weight 0. Legacy
AI-only subpopulation kept behind the flag being off.

Bug found by the first smoke: Guice handed the chooser a *fresh* copy of the
LLM strategy, so the identity check against the StrategyManager's copy failed
and no query was ever sent (silent fall-back to the lottery). Fix: the LLM
strategy is bound as a singleton. Second smoke (4 iterations, panel 20,
budget 3, warmed 10 % ground) sends queries as expected.

**Panel smoke result** (`…-panel20q3`, registered): 12 queries over 4
iterations, 11 applied, 0 tool parsing/verification/execution failures, no
round-cap hits; one agent stopped calling tools after 3 retries (known
failure mode). The ground did not move (executed score 20.16 → 20.15, 0
stuck), as it must with 12 queries on 8,460 agents. Selection reasons were
all NEVER_REVIEWED because a fresh panel is reviewed first; the score-drop
trigger only bites once the panel has been through once (20 agents / 3 per
iteration → from iteration 8). Speed is the problem: median 375 s per agent,
~18 min per iteration for 3 queries. The 27B is mostly waiting on itself
(3–9 tool rounds, each 30–45 s, plus a model reload between rounds because
mari's keep-alive is ~0 s: `keepAlive` config, default "10m", added).
WP3 (one-shot precomputed context) is therefore next, before any 200-panel
campaign run.

## 2026-09-16 (evening) — First 3×3 on the fast 27B: clean protocol, two ugly truths

**Run**: 3 agents × 3 LLM iterations, seed 4721, 27B + gpuLayers + local
embedder (registered). Protocol/persona checkpoints all green: 9/9 plans
applied, 66 tool calls with 0 hallucinated names / 0 bad args, persona voice
+ trait grounding in 9/9 conversations, no loops, no contradictions.
Per-agent 2.3–6.7 min; iteration 3 replanning took 24 min vs 12 for
iteration 2 (mari contention).

**Ugly truth 1 — a legal-looking plan that freezes the agent.** Two
commuters (27F and 62M, employed, car always) were switched car → walk/pt/walk
for both commute legs, copied verbatim from `router_tool`'s output. In the
mobsim each left home, walked to the stop, started `pt interaction` — and
never moved again (events: 1 departure, 1 arrival, 1 actstart, nothing else).
Their executed score "improved" from −44/−37 to +5.8/+5.1 because an
unperformed work activity is not penalised while the stuck car trip was, and
ExpBeta then kept that plan. **Cause**: `ActivityDTO.toBaseClass` restored
stage activities with neither end time nor maximum duration; MATSim's router
gives them `maximumDuration = 0`. **Fix**: interaction-type activities now get
`maximumDuration 0` when no end time is given (`ActivityDTO.isStageActivityType`);
unit test `ActivityDTOStageActivityTest`. Lesson for the guide: the score is
not a safe ground truth on its own — trips/events (checkpoint 4) caught this.

**Ugly truth 2 — the ground is gridlocked.** Full-scale Sioux Falls (84,110
agents, flowCapacityFactor 1.0, stuckTime 3600 s, strategies at 1 % each,
`lastIteration 3000` in the example config): at it.0 only 15.6k of 83.0k car
departures arrive; 67.3k cars are still en route at the 30:00 end and get
`stuckAndAbort` (75.8k stuck agents incl. 8.5k pt riders whose buses sit in
the same jam). The old 10-iteration run still had 71k stuck at it.10. Every
LLM experiment so far — laptop era included — ran on this ground. Score
comparisons llm-vs-default in the first tens of iterations are therefore
meaningless; the third agent (63M retired) "deliberately kept" its car plan
three times and was stuck every day, exactly like the rule-based majority.

**Decision needed**: evaluate on a healthy ground. Options: (a) rule-based
warm-up (mobsim is only ~13 s/iteration; with boosted ReRoute/mode-choice
weights a few hundred iterations ≈ 1–2 h) and start LLM runs from the warmed
`output_plans.xml.gz` (runner needs a `--plans-file` option); (b) a 10–25 %
population sample with matching capacity factors (seconds per iteration, warm
up in minutes), which is the usual MATSim practice for method development.
Either way the LLM subpopulation also needs a re-route fallback or the
router tool in the prompt's default path, otherwise a kept plan keeps its
jammed route forever.

## 2026-09-16 — Resume after pivot: 27B does not fit mari; right-sizing forced

**Setup**: `ai-matsim-start` now opens the mari tunnel itself (diary
2026-09-16). Offline verification passed (compile, RunMatsimTest, new
`scripts/probes/native_probe.sh` — 4 native-API checks incl. embedder).

**Run**: 27B smoke, 2 agents / 1 LLM iteration / persona + cmp / cap 10 /
seed 4711 (registered). Both agents applied a plan (6 and 4 rounds, all 10
tool calls valid) — but 11.5 and 7.1 min per agent, ~3× worse than July.

**Timing decomposition** (10 rounds, 1060 s): model load 232 s (22%), prompt
eval 125 s (12%), generation 701 s (66%) at only 12 tok/s. Every round paid
a ~20 s model load and re-evaluated the full 6–7.5k-token prompt (no cache).

**Root cause (probe sweep, no MATSim)**: on mari (2× RTX 2080 Ti 11 GB, one
other user holding ~0.7 GB) the Q4 27B fits fully only at ctx 4096 (16.8 GB,
27 tok/s, warm load 8 s). At ctx 8192/10240/12288 it spills 1.8 GB to CPU,
generation halves to 14 tok/s, and Ollama 0.32 reloads it on *every* call
(warm load 20 s) — the embedder swap is a second, smaller cause (a
`/api/embed` call evicts the 27B outright). Our prompts already reach 7.5k
tokens, so ctx 4096 is not an option ⇒ the 27B is not viable on mari with
this prompt size. Thinking tokens (68% in July) were never the first-order
problem here.

**Decision**: model right-sizing before anything else (as the pivot ordered).
`qwen3.6` has no size below 27B; candidate is `qwen3.5:9b` (the laptop-era
persona-win model, ~6 GB, fits at ctx 12288 with the embedder resident).
Profile added; pulled to mari; same smoke config rerun.

**9B results (same config, seed 4711, n=2 — indicative only)**:
- 9B fits fully at ctx 12288 and 16384; warm reload 0.8 s; **65 tok/s**
  (27B: 14). Generation per round 15 s vs 70 s.
- Run 1 (embedder still on mari): 1/2 applied, 8.3 + 6.2 min/agent — model
  load was 55% of wall (an `/api/embed` call evicts the chat model every
  round on mari).
- Fix: dedicated embedding endpoint (new `embeddingHost`/`embeddingPort` in
  `LLMConfigGroup`, `--embedding-host/--embedding-port` in the runner,
  default = same server as chat). Embedder now runs on the laptop's own
  GPU via a second local Ollama on :11435 (started by `ai-matsim-start`).
- Run 2 (embedder local): **2/2 applied, 0.5 + 3.4 min/agent, 233 s total**
  vs 1119 s for the 27B — ~4.8× faster on the same 2 agents, with a plan
  applied for both.
- Residual ~20 s "load" on random rounds: replaying the exact logged
  request to mari showed `llama3.1:8b-instruct-q4_0` appearing in
  `ollama ps` mid-experiment — **another user runs Ollama jobs on mari**,
  and each of their loads evicts/queues ours. Outside our control; expect
  run-time variance while mari is shared. Journal not readable to confirm
  volume.

**Caveats**: n=2 agents, one seed, temperature 0.3 is not seeded → the
27B-vs-9B *quality* comparison (2/2 vs 2/2, but 9B run 1 capped one agent
in an evaluate_plan loop) needs the multi-seed rebaseline. Speed conclusion
is robust: the 27B cannot be made fast on mari with 6–7.5k-token prompts.

**CORRECTION — the 27B IS viable (PI's LLM specialist was right)**: the
spill was Ollama's conservative memory *estimate*, not real VRAM pressure.
Forcing full residency with the `num_gpu` request option (new profile key
`gpuLayers: 999` → `LLMConfigGroup.gpuLayers` → `OllamaNativeChatRequest`)
puts the Q4 27B entirely on GPU at ctx 12288 (17.4 GB of ~21.8 usable) at
**26 tok/s**, warm reload 0.8 s, prompt cache working.

**Final like-for-like (2 agents, seed 4711, cap 10, persona+cmp)**:

| config | applied | rounds | total LLM wall | gen tok/s | load |
|---|---|---|---|---|---|
| 27B as of July (est. spill + remote embedder) | 2/2 | 10 | 1119 s | 12 | 232 s |
| 9B + local embedder | 2/2 | 6 | 233 s | 65 | 121 s* |
| **27B + gpuLayers + local embedder** | 2/2 | 12 | **397 s** | 26 | 19 s |

\* 9B "load" was contention from another user's llama3.1 jobs on mari.

The 27B is 2.8× faster than before and stays the campaign model: it showed
better tool discipline in July and today (no evaluate_plan loops; the 9B
capped one agent in its first run). The 9B stays as a fast fallback profile.
Both fixes are model-independent (any Ollama model benefits from the local
embedder; `gpuLayers` matters only when the estimator under-offloads).

**Next**: WS-0 rebaseline on the 27B with the local embedder
(`scripts/ws0-rebaseline.sh`, defaults to the 27B; ~3.3 min/agent ⇒ 5 agents ×
10 it ≈ 2.8 h/seed, plus variance from other users' jobs on mari). A2
parallelism on the 27B is VRAM-bound (~4 GB free at ctx 12288 ⇒ at most 1
extra sequence, and it needs `OLLAMA_NUM_PARALLEL` on mari = collaborator's
call); A1 thinking reduction is the lever we fully control (68% of generated
tokens are thinking).

## 2026-07-23 (evening) — WS-0 fixes in, rebaseline batch launched

**Fixes** (all offline-verified: compile + RunMatsimTest + embed/chat probes):
1. **Qdrant contamination**: collection name is now per-run (derived from the
   self-identifying output dir name) — experience docs can no longer leak
   between runs/seeds. Runner-level change only.
2. **Activity-type prompt gap**: both prompt variants now state that activity
   type names are scenario-defined and must never be renamed (closes the
   `secondary`→`other` misconception from the first 27B run).
3. **Embedder upgrade** (collab suggestion): `nomic-embed-text` (2024, 768-d)
   → `qwen3-embedding:0.6b` (2025, 1024-d, MTEB-leading in class, same family
   as chat models). New CLI option `--embedding-model`; dimension picked up
   automatically at collection creation. Probe: 1024-d vectors OK.

**Batch**: WS-0 rebaseline = 3 seeds (4711/4712/4713) × 5 agents × 10
iterations, persona + comparison tools, cap 10, qwen3.6:27b. Early-stop at
<60% success between seeds; GPUs evicted after each seed. Expected 4–10 h.

**ABORTED mid-seed-1 by PI decision**: 4–10 h wall time is unacceptable as a
working rhythm. Seed 4711 partial output is void (do not analyze). **Pivot:
speed becomes the gate for everything** — WS-A2 (parallel agents) + A1
(thinking reduction) + model right-sizing move BEFORE the rebaseline; the
rebaseline reruns only once an iteration-scale run fits in tens of minutes.
Timing decomposition backs this: generation is 91% of wall time, 68% of
tokens are thinking, and the 27B pays a permanent ~1 GB CPU-offload tax.

## 2026-07-23 — Campaign start: remote 27B online, first persona signals

**Setup**: LLM inference moved to mari.dinfo.unifi.it (qwen3.6:27b, SSH tunnel,
zero code changes). Envelope probe-validated: ctx 12288 / maxTokens 6144,
21.6 tok/s, 1.0 GB CPU spill. See diary 2026-07-23 for the full setup record.

**Runs**: wire test (qwen3.5:0.8b) + first 27B run, both 2 agents / 1 LLM
iteration / persona variant / round cap 4 (deliberately tight). Registered in
`runs.md`.

**Findings**:
- Persona emergence visible immediately on the 27B: both agents reasoned
  first-person with attribute grounding ("as a 53-year-old woman with a car…",
  "At 80 years old and retired… I don't want to be on the road too long at my
  age"). The 9b-era emergence result reproduces and strengthens with scale.
- Tool discipline: 7/7 tool calls valid across both models' runs (0 parsing /
  verification / execution failures). The 27B used validate_timing before
  extract_plan unprompted, and available_modes at both trip ends before
  reasoning about modes.
- Deliberate plan-keeping observed (agent 20997_1: verified then kept original
  plan) — correct behavior that naive success metrics would miss.
- Failure mode found: round cap 4 truncated a *productive* investigation
  (agent 15840_2, 5 clean rounds). Default cap 10 for real runs.
- Prompt gap found: model believed `secondary` is not a valid activity type,
  planned to rewrite it to `other`. Needs the WS-0 prompt fix.
- Timing reality: 87 s and 247 s per agent conversation → ~1.5–4 min/agent/
  iteration. This is the number WS-A must cut.

**Next**: WS-0 — Qdrant contamination fix, activity-type prompt fix, then
3-seed × 5-agent × 10-iteration rebaseline.
