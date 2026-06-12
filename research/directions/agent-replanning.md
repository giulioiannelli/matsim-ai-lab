# LLM Agent Replanning Quality

## Current state

The LLM receives an agent's daily plan as JSON (PlanDTO) and can:
1. Reason about mode choices (car, PT, bike, walk)
2. Call `router_tool` to get routes from MATSim's TripRouter
3. Call `pull_additional_context` to retrieve past travel experiences from Qdrant
4. Return a modified plan via `extract_plan`

## Key challenges

### Physical plausibility
- Car/bike continuity: vehicle must be at origin for a car/bike leg
- PT chain structure: walk → pt interaction → pt → pt interaction → walk must be treated as one trip
- Activity sequence: must alternate activity-leg-activity, start and end with activity
- Route validity: legs must connect consecutive activities

### Reasoning speed vs quality tradeoff
- qwen3.5 (9.7B): good tool calling, but extended thinking on complex plans (8+ min for PT)
- Smaller models hallucinate tool names
- Need to find the sweet spot: enough reasoning for correct plans, not so much the simulation stalls

### Prompt engineering
- System prompt is ~800 tokens of rules — can we compress?
- PT handling rules are complex — can we pre-process PT chains before sending to LLM?
- Should we show the LLM only activities (not intermediate legs) for mode choice, then route separately?

## Metrics to track
- Time per agent per iteration
- Tool calling accuracy (correct tool name, valid arguments)
- Plan validity (passes ExternalValidator)
- Mode choice changes vs original plan
- Score improvement/degradation after LLM replanning

## Experiment ideas
- Compare plan quality across models (qwen3.5, qwen2.5:7b, API models)
- Ablation: remove routing tool, let LLM only change modes → measure physical plausibility
- Few-shot examples in prompt: show LLM a good plan modification example
- Pre-simplify PT chains: collapse walk-pt-walk into single "PT trip" before sending to LLM
