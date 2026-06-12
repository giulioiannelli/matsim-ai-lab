# Meaningful Reasoning Plan

Status: planning (2026-04-15). Supersedes the earlier "just add more tools" direction once tool scaffolding alone was shown to produce mechanical template-filling rather than real agency.

## Problem we're solving

qwen2.5:7b + grammar-constrained dispatch now gives 100% JVM-level success at ~25s per conversation. But reading the transcripts revealed the reasoning is empty: the LLM fills a fixed "verify → verify → extract" template with zero evaluation of route quality, zero mode-choice deliberation, zero reference to person attributes, and in one case skipped routing entirely while claiming it had done it. **Success masked hollow behavior.** We need agents that actually *think like humans* about transport choices.

## Four tracks (they compound)

### 1. Bring back reasoning models — but accept grammar is off for them
Reasoning models (qwen3.5, deepseek-r1 family) exhaust token budgets on internal thinking when forced through JSON-schema grammars — empirically verified in offline probes. The grammar path remains valid for non-thinking models (qwen2.5:7b, llama3.1:8b). Our architecture must support both:
- **Grammar ON** (qwen2.5:7b, llama3.1:8b): protection against hallucinated names and malformed args. Use for production comparisons where mechanical safety matters.
- **Grammar OFF** (reasoning models): native `tools:` field, accept that hallucinated names will appear, invest in the tool-name-aliaser (already implemented, currently off) as a safety net. Use for quality-of-reasoning experiments.
- Both share the same extract_plan fallback, so failure modes are bounded in both worlds.

### 2. Prompts that force self-questioning
Current prompt is a protocol spec ("use tools, don't invent routes"). No question the LLM must answer in natural language, so it skips reasoning. New prompt discipline:
- **Pre-routing question**: "Before picking a mode, state: which modes can this person use at the origin, and which is plausible given their attributes?" Forces mode-check reasoning into the thought field.
- **Post-routing question**: "Compare the router result to the original. Is the travel time similar, better, or catastrophically worse? If worse, what would you change?" Forces evaluation.
- **Pre-extract question**: "Summarise the final plan in one sentence. Does every activity keep its original type and location?" Forces consistency check.
- Each question is short (< 15 tokens) and shows up in the user message, not system prompt, so it's scoped per round.
- Store these as versioned prompt files under `prompts/v3_reasoning/` (new subfolder) to A/B-test.

### 3. Comparison tools, not raw-data tools
The existing tools hand the LLM raw MATSim objects. Small models don't extract signals from raw; they template-match. Replace/augment with decision-shaped tools:
- `compare_routes(current, candidate)` → `{delta_travel_time_s, delta_distance_m, verdict: "faster" | "slower" | "similar", reason_to_switch: string}`. Forces the LLM to see a scalar rather than two blobs.
- `evaluate_plan(plan)` → `{realism_score, issues: ["leg2 arrival after next activity end", ...]}`. Deterministic; derived from timing + facility constraints.
- `suggest_modes(leg, persona)` → top-3 ranked modes with one-sentence rationale each. Lets the model pick, not invent.
- `persona_check(mode, persona)` → `{consistent_with_history: bool, explanation: string}`. Grounds mode choice in the agent's identity.

Each new tool is tiny, opt-in, and purely deterministic in Java. No new nondeterminism, no GPU cost. They slot into the existing `ITool` registry.

### 4. Persona memory: intelligent RAG for small models
Qdrant is running but unused beyond person-attribute dumps. The memory vision (`project_persona_memory_vision.md`) is to accumulate structured persona representations across runs. For small local models the key constraint is context size — we cannot flood the prompt with retrieved chunks.

**Two-layer persona:**
- **Structured persona** (≤ 200 tokens per agent, stored in Qdrant with `type:persona`): JSON of preferred_modes, typical_departures, behavioral_notes. Pre-injected into the user message at conversation start. Small models see it immediately without tool calls.
- **Episodic persona** (raw conversation snippets, stored with `type:episode`): retrievable via `pull_additional_context` but only when the LLM explicitly asks and filters by personId. Noisy but available when the model reaches for it.

**Distillation pipeline** (Python, post-simulation, no GPU at MATSim time):
- After each run, parse JSONL + final plans; extract mode frequencies, timing patterns, successful rationales. Write back as structured persona JSON. Runs in `matsim_py_analysis/persona/` (new subpackage).
- The `persona-distiller` is idempotent and re-runnable on any historical output directory.

**Transfer across scenarios**: tag persona fields as `scenario-specific` (facility IDs, routes) vs `transferable` (mode preferences, time-sensitivity). Only transferable fields migrate when we port personas to a new network.

## Local GPU constraint (RTX 3080 Laptop, 16 GB VRAM)

All model choices must fit in 16 GB and run at a tolerable step time (< 60s per conversation as the upper limit before early-stop kicks in). Empirically observed and inferred sizes:

| Model | Approx VRAM (Q4_K_M) | Reasoning quality | Grammar-compatible | Use case |
|---|---|---|---|---|
| qwen2.5:7b | ~5 GB | shallow (current) | yes | fast iteration baseline |
| llama3.1:8b | ~5.5 GB | shallow | yes | cross-check non-qwen |
| **deepseek-r1-distill-qwen:7b** | ~5 GB | **good (distilled r1)** | likely no (thinking) | first reasoning-model candidate |
| **qwen2.5:14b** | ~8.5 GB | moderate | yes | bigger-but-fast experiment |
| deepseek-r1-distill-qwen:14b | ~8.5 GB | better reasoning | no | second reasoning candidate |
| qwen3:14b | ~9 GB | very good but slow (244s/call earlier) | no | only if we accept one agent per run |
| qwen2.5:32b Q3 | ~14 GB | strong | yes | tight fit, treat as a stretch test |

Decision rules for every experiment:
- Maximum 4 AI agents per run while testing new models (the bottleneck is per-conversation latency).
- A model gets a 1-iteration gate first. If any agent exceeds 90 s or produces no extract_plan, kill.
- Never leave a reasoning model to run unattended past 3 iterations.
- API-based models (OpenAI o-mini, Claude Haiku) will be tried only once the prompt+tools+persona stack is validated locally — they are a validation control, not a daily driver.

## Execution plan (phased, each phase independently reversible)

### Phase A — Comparison tools (1-2 days, no new sim until tools done)
- Implement `compare_routes`, `evaluate_plan`, `suggest_modes`, `persona_check` under `tools/Implement/comparison/`.
- Each tool: unit test + deterministic offline fixture. Gate at 100% unit-test pass.
- Register behind a feature flag (`MATSIM_LLM_COMPARISON_TOOLS=on`) so they don't affect existing runs.
- Single 1-iter grammar sim with `qwen2.5:7b` + comparison tools to verify they compile, register, and get called.

### Phase B — Prompt v3 with explicit questions
- Add `prompts/v3_reasoning/` with `system.txt` (protocol) and `per_round_questions.txt` (the explicit question set).
- Load the system prompt via the existing prompt registry; wire a per-round question into the user message the way Level-2 pre-injection already works.
- Offline gate: curl probe qwen2.5:7b with the new prompt, verify the `thought` field now contains the answer to each question.
- 1-iter sim. Compare transcript quality to the 4 current baselines — look for substantive reasoning (not just template).

### Phase C — Persona memory
- Add `matsim_py_analysis/persona/distiller.py`: takes an output dir, produces structured JSON per agent, writes into Qdrant collection `matsim_persona`.
- Add `tools/Implement/persona/PersonaReadTool.java`: exposes `get_persona(personId)` returning the stored JSON; for small-model mode, pre-inject at conversation start instead of exposing as a tool.
- Bootstrap personas from MATSim person attributes for first run.
- 1-iter sim: verify the LLM's thoughts reference persona content.

### Phase D — Reasoning model round
- Pull deepseek-r1-distill-qwen:7b and deepseek-r1-distill-qwen:14b (`ollama pull`).
- Offline probe each with the existing curl harness under BOTH grammar-on and grammar-off; record schema acceptance and thinking-time.
- Run 1-iter sim on the best candidate with Phase A+B+C stack. Compare transcripts against qwen2.5:7b on identical prompts.
- Decision gate: does the reasoning model produce more substantive thoughts than qwen2.5:7b for the same tool ecosystem? If yes, keep; if no, defer.

### Phase E — Bigger non-reasoning models (stretch)
- Try qwen2.5:14b with grammar on. If it still fits the time budget, compare reasoning substance against 7b.
- If 14b is too slow, stop there — we know the ceiling on the laptop.

### Phase F — API control (later)
- One day of Claude Haiku / o-mini via API with identical tool stack and prompt. Not a daily driver; a calibration point to tell us how much of the shallowness is "small model" vs "bad tool design".

## Success criteria (per phase)

Every phase must clear these gates before the next starts:
1. Offline tests pass (Java unit + LLM curl probes + synthetic dry-run).
2. 1-iter sim success rate ≥ previous phase (no regression, per our always-improve rule).
3. Transcript audit: at least one meaningful reasoning statement per conversation (comparison, attribute reference, counterfactual, or explicit constraint check).
4. Output dir name encodes model + params + active flags for later comparison.

## What we will NOT do yet
- Scale beyond 5 AI agents per run (GPU budget).
- Touch the baseline runner's default behavior (all new features stay opt-in).
- Introduce a second scenario (Kelheim) until Sioux Falls reasoning is meaningful.
- Rewrite MATSim-side code structurally (the `submit()` duplication refactor stays deferred).

## Cross-references
- Baseline findings: [agent-reasoning-analysis.md](agent-reasoning-analysis.md)
- Tool registry: `research/guides/03-llm-plugin-architecture/tool-registry.md`
- Persona vision: memory file `project_persona_memory_vision.md`
- Current sample transcripts: `output/siouxfalls-qwen2.5-7b-T0.3-N4096-grammar/chat_transcripts.txt`
