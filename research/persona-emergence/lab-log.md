# Lab log — persona emergence campaign

Newest entries on top. One entry per working session that produced or analyzed
data. Session-level operational logs stay in `.claude/diary/`.

---

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
