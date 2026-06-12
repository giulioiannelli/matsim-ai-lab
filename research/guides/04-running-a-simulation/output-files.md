# Output Files

All simulation output goes to `output/<scenario-name>/` (gitignored).

## LLM-specific outputs

| File | Format | What it contains | Read with |
|------|--------|-----------------|-----------|
| `llm_chat_log_ChatLog_combined.jsonl` | JSON Lines | Full LLM conversations: prompts, responses, reasoning traces, tool calls, timing, token counts | `matsim-py-analysis` log parser |
| `llm_person_stats_combined.csv` | CSV | Per-agent per-iteration stats: success, duration, tokens, tool calls, failures | pandas |
| `ITERS/it.N/llm_person_stats.csv` | CSV | Same stats but for single iteration | pandas |

### Chat log structure (one JSON object per LLM call)

```json
{
  "traceId": "6eff9422-...",
  "timestamp": "2026-04-14T04:36:49",
  "iteration": 1,
  "modelName": "qwen3.5",
  "temperature": 0.3,
  "maxTokens": 4096,
  "httpStatus": 200,
  "durationMs": 24132,
  "requestBody": "{\"messages\": [...], \"tools\": [...]}",
  "responseBody": "{\"choices\": [{\"message\": {\"reasoning\": \"...\", \"tool_calls\": [...]}}], \"usage\": {...}}"
}
```

The `responseBody` contains the LLM's reasoning (chain-of-thought) and tool calls. This is the primary data source for NLP analysis.

### Person stats columns

| Column | Description |
|--------|-------------|
| iteration | MATSim iteration number |
| personId | Agent ID |
| success | Did the LLM produce a valid plan? |
| failureType | NONE, NO_TOOL_CALL_AFTER_RETRIES, MAX_ITERATION_REACHED, etc. |
| llmRounds | Number of LLM API calls for this agent |
| totalToolCalls | Total tool invocations |
| toolParsingFailures | Times the LLM produced unparseable tool calls |
| toolVerificationFailures | Times tool arguments failed validation |
| toolExecutionFailures | Times tool execution threw an error |
| durationMs | Total wall clock time for this agent's replanning |
| promptTokens / completionTokens / totalTokens | Token usage |

## MATSim standard outputs

| File | Format | What it contains |
|------|--------|-----------------|
| `output_plans.xml.gz` | MATSim XML | Final agent plans with scores |
| `output_events.xml.gz` | MATSim XML | All events from the last iteration |
| `output_network.xml.gz` | MATSim XML | Network used |
| `output_trips.csv.gz` | CSV | Trip-level data: origin, destination, mode, time |
| `output_legs.csv.gz` | CSV | Leg-level data: route, distance, duration |
| `output_persons.csv.gz` | CSV | Person-level summary |
| `scorestats.csv` | CSV | Min/max/avg/executed scores per iteration |
| `modestats.csv` | CSV | Mode share per iteration |
| `stopwatch.csv` | CSV | Wall clock time per iteration phase |
| `logfile.log` | Text | Full MATSim log |

## Per-iteration outputs

```
ITERS/
├── it.0/
│   ├── 0.plans.xml.gz      # Plans at start of iteration 0
│   └── 0.events.xml.gz     # Events during iteration 0 (if writeEventsInterval matches)
├── it.1/
│   └── ...
└── it.10/
    ├── 10.plans.xml.gz
    └── 10.events.xml.gz
```

Plans are written every `writeEventsInterval` iterations (default: only first and last).

## SimWrapper dashboards

| File | Content |
|------|---------|
| `dashboard-1.yaml` | Overview dashboard config |
| `dashboard-2.yaml` | Mode share charts |
| `dashboard-3.yaml` | Score evolution |
| `simwrapper-config.yaml` | SimWrapper settings |

View with: `simwrapper here` in the output directory, or upload to https://simwrapper.app

## Data flow: Java output → Python analysis

```
MATSim simulation
  ├─ writes → llm_chat_log_ChatLog_combined.jsonl
  ├─ writes → llm_person_stats_combined.csv
  ├─ writes → output_trips.csv.gz
  └─ writes → scorestats.csv, modestats.csv

matsim-py-analysis reads these:
  ├─ parsers/chat_log.py → ChatLogEntry objects
  ├─ analysis/llm_decisions.py → mode changes, timing, success rates
  ├─ analysis/reasoning.py → reasoning categorization, tool demand
  └─ analysis/scores.py → score evolution
```
