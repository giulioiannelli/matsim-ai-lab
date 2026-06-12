# 05 — Python Analysis (matsim-py-analysis)

The `matsim-py-analysis/` submodule handles all data science: parsing simulation outputs, analyzing LLM reasoning traces, and generating visualizations.

## How it connects to the Java side

```
Java simulation produces:                 Python reads:
  output/siouxfalls-llm-agents/
    llm_chat_log_ChatLog_combined.jsonl  → parsers/chat_log.py → ChatLogEntry objects
    llm_person_stats_combined.csv        → pandas.read_csv()
    output_trips.csv.gz                  → parsers/csv_reader.py
    scorestats.csv                       → analysis/scores.py
    modestats.csv                        → analysis/mode_share.py
    ITERS/it.N/*.plans.xml.gz           → parsers/xml_reader.py
```

There is no runtime coupling — Python runs post-hoc on completed simulation outputs.

## Quick start

```bash
# Use the matsim-ai conda environment
conda run -n matsim-ai matsim-analyze analyze output/siouxfalls-llm-agents/
conda run -n matsim-ai matsim-analyze reasoning output/siouxfalls-llm-agents/
conda run -n matsim-ai matsim-analyze bottlenecks output/siouxfalls-llm-agents/
conda run -n matsim-ai matsim-analyze tool-usage output/siouxfalls-llm-agents/
conda run -n matsim-ai matsim-analyze persona output/siouxfalls-llm-agents/   # persona emergence, repetition loops, persona<->tool contradictions
conda run -n matsim-ai matsim-analyze compare output/run-a/ output/run-b/
```

All readers handle both LLM wire formats transparently: OpenAI-compatible
(`choices[].message.reasoning`, string tool args) and Ollama-native
(`message.thinking`, object tool args, no `tool_call_id` on tool messages).
Conversations are keyed by the legacy `You are person XXXX` marker when present,
else by a stable hash of the original-plan JSON (persona prompts omit the
marker). Ground-truth MATSim person ids live in `llm_person_stats_combined.csv`.

## Submodule structure

```
matsim-py-analysis/
├── src/matsim_py_analysis/
│   ├── parsers/          # JSONL, CSV, XML parsers
│   ├── analysis/         # LLM decisions, reasoning, scores, mode share, bottlenecks
│   ├── visualization/    # Plan and network visualizations
│   ├── nlp/              # (placeholder) Sentiment analysis
│   └── cli.py            # Click commands
└── notebooks/
    └── 01_tool_design_signals.ipynb
```

## Key modules

| Module | What it does |
|--------|-------------|
| `parsers/chat_log.py` | Parses nested-JSON JSONL logs into `ChatLogEntry` objects with tool calls, reasoning traces, token usage |
| `analysis/llm_decisions.py` | Mode changes, timing distributions, success rates, plan diffs |
| `analysis/reasoning.py` | Regex-based categorization of reasoning text; identifies what a tool could eliminate |
| `analysis/bottlenecks.py` | Identifies slow conversations, wasted reasoning, tool demand signals |
| `analysis/compare.py` | Compare two simulation runs (e.g., baseline vs LLM) |

For full documentation, see the submodule's own README.
