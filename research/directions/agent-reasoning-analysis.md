# Analysis of Agent Reasoning

## Vision

Full control of I/O between MATSim loop and LLM agents, enabling deep analysis of how AI agents reason about travel decisions. This is the scientific core of the project: understanding what happens when you replace heuristic replanning with natural language reasoning.

## Data sources

### Conversation logs (`llm_chat_log_ChatLog_combined.jsonl`)
Each line contains:
- `traceId` — unique conversation ID
- `iteration` — MATSim iteration number
- `durationMs` — wall clock time for the LLM call
- `requestBody` — full prompt sent to LLM (system prompt + plan JSON)
- `responseBody` — full LLM response including:
  - `reasoning` — chain-of-thought (when thinking enabled)
  - `tool_calls` — structured tool invocations
  - `usage` — token counts (prompt, completion, total)
- `httpStatus` — API response code

### Agent stats (`llm_person_stats_combined.csv`)
Per-agent per-iteration metrics from the replanning module.

### MATSim outputs
- Plans XML: what the agent actually does each iteration
- Events XML: what happened during simulation
- Score stats: how plan scores evolve

## Analysis dimensions

### 1. Reasoning content analysis
- **Sentiment/tone**: Is the LLM cautious? Confident? Does it express uncertainty about mode choices?
- **Decision justification**: What reasons does the LLM give for changing (or not changing) a plan?
- **Information usage**: Does it reference travel times, distances, past experiences from RAG?
- **Error patterns**: When does it hallucinate routes, violate constraints, or produce invalid plans?

### 2. Decision pattern analysis
- Mode choice distributions: How often does the LLM switch modes vs keep original?
- Which directions: car→PT? PT→car? Walk→bike?
- Correlation with plan complexity: simple plans (car commute) vs complex (multimodal)
- Evolution over iterations: does the LLM learn from score feedback?

### 3. Performance analysis
- Token efficiency: reasoning tokens vs output tokens
- Time breakdown: thinking time vs tool calling time vs network overhead
- Timeout patterns: which plan types cause long reasoning?

### 4. Comparison with MATSim heuristics
- Score convergence: LLM agents vs SubtourModeChoice/ReRoute agents
- Mode share evolution: do LLM agents produce realistic mode splits?
- Behavioral realism: do LLM agents make "human-like" choices?

## Tools needed
- Python NLP pipeline: tokenization, sentiment analysis (VADER or transformer-based)
- JSON log parser: extract reasoning traces, tool calls, timing
- Visualization: matplotlib/seaborn for distributions, plotly for interactive
- Comparison framework: align LLM agent scores with baseline scores per iteration

## Implementation plan
- Phase 1: JSON log parser + basic stats (timing, token counts, tool accuracy)
- Phase 2: Reasoning content extraction and categorization
- Phase 3: Sentiment analysis on reasoning traces
- Phase 4: Cross-iteration analysis (do agents improve?)
- Phase 5: Comparison dashboards (LLM vs heuristic)
