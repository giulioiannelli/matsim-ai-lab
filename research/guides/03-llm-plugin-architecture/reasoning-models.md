# Reasoning models in the MATSim LLM plugin

**Status**: Phase 1 deliverable of `glowing-leaping-dragon` plan (2026-04-20).
**Scope**: how reasoning models are wired through the plugin, what the profile
system covers, and the empirical constraints that drove §1.3 Rule B of the plan.

## Why a dedicated path

Sioux Falls replanning needs agents that weigh alternatives, ground decisions
in personal attributes, and perform numeric checks on plans. Non-thinking models
(qwen2.5:7b, llama3.1:8b) produce templated tool-call sequences without this
deliberation — the Phase 0 audit measured strict-rubric behavioral reasoning at
0–3% on those runs. Reasoning models (qwen3.5, deepseek-r1-distill) produce a
dedicated reasoning stream that the plugin can now capture and surface.

## How reasoning text reaches the CSV

```
Ollama /v1/chat/completions
      │
      ▼
response JSON: choices[0].message.reasoning  (separate field, ~2000-3000 chars)
      │
      ▼
OpenAiChatResponse.Message.reasoning   (field added Phase 1)
LmStudioChatResponse.Message.reasoning (field added Phase 1)
      │  postBuildCleanup()
      ▼
response.getReasoning()  — also falls back to <think>...</think> tags in content
      │
      ▼
DefaultChatManager.resolveReasoningTokens()
  · uses usage.completion_tokens_details.reasoning_tokens when > 0
  · otherwise estimates as len(reasoning) / 4  ← Ollama fallback
      │
      ▼
ChatStats.reasoningTokens   →   llm_person_stats_combined.csv
```

Historical runs logged `reasoningTokens=0` because Ollama does not split
`usage.completion_tokens` into reasoning vs output subcategories and the earlier
code did not read `message.reasoning`. After Phase 1 both paths populate the
counter correctly (verified end-to-end on qwen3.5, 2026-04-20 validation sim:
`reasoningTokens=869` against `completionTokens=1267`).

## Model profiles — `config/model-profiles.yaml`

Each model gets an entry:

```yaml
profiles:
  "qwen3.5":
    maxTokens: 4096
    temperature: 0.3
    enableThinking: true
    isReasoning: true
    thinkingTokenCap: 2048
```

Fields:

| Field | Meaning | Consumed by |
|---|---|---|
| `maxTokens` | hard cap on completion tokens per round | request serialization |
| `temperature` | sampling temperature | request serialization |
| `enableThinking` | send `enable_thinking=true` on requests | `OpenAiChatRequest`, `LmStudioChatRequest` |
| `isReasoning` | model emits reasoning stream; forces grammar OFF | §1.3 Rule B enforcement |
| `thinkingTokenCap` | soft cap; rounds above it tag `thinkingTokenCapHit` (advisory) | `ChatStats.thinkingTokenCapHit` |

The loader is `matsimBinding.profile.ModelProfileLoader`; the applier is
`ModelProfileApplier.apply(LLMConfigGroup)` — called once from
`RunSiouxFallsLLMAgents` before `controler.run()`.

A missing profile file or unknown model name is non-fatal: the applier logs a
note and the runner falls back to `LLMConfigGroup` defaults. To add a new model
you only need to append an entry to the YAML — no Java changes required.

## §1.3 Rule B — grammar ≠ thinking

Empirically verified (Phase 0 diary, `siouxfalls-llm-grammar-only` had 0 CSV
rows): grammar-constrained decoding via `-Dmatsim.llm.grammar=true` is
incompatible with reasoning models because the model exhausts `max_tokens`
inside `<think>` before the grammar-pinned tool-call JSON can emit.

The applier enforces this at runtime:

```
if (profile.isReasoning() && Boolean.getBoolean("matsim.llm.grammar")) {
    System.err.println("WARNING: disabling -Dmatsim.llm.grammar per Rule B");
    System.setProperty("matsim.llm.grammar", "false");
}
```

So for non-thinking models (e.g. `qwen2.5:7b`) the grammar path remains
available via `-Dmatsim.llm.grammar=true`; for reasoning models the flag is
silently neutralized. No fuzzy/aliasing safety net is introduced (plan
non-goal); hallucinated tool names propagate through `DefaultToolManager.getByName()`
as `toolVerificationFailures` and the agent's original plan is preserved.

## Tested models (Phase 1)

| Model | `isReasoning` | `enableThinking` | Status |
|---|:---:|:---:|---|
| `qwen3.5` | yes | yes | validated end-to-end; reasoning captured (3477 chars → 869 tokens in one round) |
| `qwen3.5:latest` | yes | yes | alias; same profile |
| `qwen2.5:7b` | no | no | non-thinking baseline; grammar-compatible |
| `llama3.1:8b` | no | no | grammar-compatible alternative (Phase 0 probe) |
| `deepseek-r1-distill-qwen:7b` | yes | yes | profile ready; not yet pulled |
| `deepseek-r1-distill-qwen:14b` | yes | yes | profile ready; not yet pulled |
| `qwen3:14b` | yes | yes | slower path (historical 244s/call); reserved for small-agent experiments |
| `gemma4:e4b` | no | no | small edge model; grammar-compatible |

## Offline probe

`scripts/probes/phase1_reasoning.sh [MODEL]` — hits Ollama with 5 varied prompts
and asserts `message.reasoning` is populated on every response. Exit 0 = model
surfaces reasoning through our endpoint path. This is a precondition for adding
the model to `isReasoning: true` profiles.

## What's *not* in Phase 1

- Two-phase decoding (call 1 reasoning, call 2 grammar-locked tool call) — that
  is Phase 2 of the plan, see `/home/opisthofulax/.claude/plans/glowing-leaping-dragon.md`.
- Hard enforcement of `thinkingTokenCap` (truncation at the request level). The
  cap is currently advisory only — a cap hit shows up in `ChatStats.thinkingTokenCapHit`
  for post-hoc analysis. Hard enforcement would require a streaming request and
  is deferred.
- A separate `/api/chat` native endpoint path. All requests go through the
  OpenAI-compatible `/v1/chat/completions` endpoint which empirically already
  passes `message.reasoning` through for qwen3.5.

## References

- Plan: `/home/opisthofulax/.claude/plans/glowing-leaping-dragon.md` §1.3, Phase 1
- Phase 0 diary: `.claude/diary/2026-04-20.md`
- Validation run: `output/siouxfalls-qwen3.5-T0.3-N4096/llm_person_stats_combined.csv`
