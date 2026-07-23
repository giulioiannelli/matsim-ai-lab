# Lab log — persona emergence campaign

Newest entries on top. One entry per working session that produced or analyzed
data. Session-level operational logs stay in `.claude/diary/`.

---

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
