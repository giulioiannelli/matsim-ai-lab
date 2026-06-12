# Prompts

> **Source file**: `prompts/IndividualPrompt.java`

## Prompt structure

Each LLM conversation has two prompts:

1. **System prompt** — set once per agent at startup, defines the LLM's role and rules
2. **User prompt** — sent each iteration with the agent's current plan

## System prompt (IndividualPrompt.toolFirstSystemPrompt)

The system prompt tells the LLM:
- "You are an AI agent controlling a single person in MATSim"
- Rules for tool usage (always use tools, never invent routes)
- Plan reconstruction rules (maintain activity-leg-activity order)
- Mode continuity rules (car/bike must be physically available)
- PT trip handling (treat walk→pt→walk as one trip)
- Output rules (only communicate through tool calls)

Appended per-agent: `" You are person 9285_1"`

## User prompt (IndividualPrompt.toolFirstPlanExtractionPrompt)

Sent each iteration with the plan JSON:
- "Here is the original daily plan"
- "Reconstruct a reasonable version staying close to the original"
- "Verify routes against current simulation state"
- "Be careful with car/bike continuity"
- "Return through extract_plan tool"

## Where to refine

The system prompt is ~800 tokens of rules. Key opportunities:
- **Compress**: Remove redundant instructions, use shorter phrasing
- **Few-shot examples**: Show one good plan modification example
- **Pre-process plans**: Collapse PT chains before sending, reducing rule complexity
- **Persona-specific prompts**: Different instructions for car commuters vs PT users
- **Score feedback**: Tell the LLM how previous plans scored
