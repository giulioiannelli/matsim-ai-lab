---
name: matsim-analyze
description: "Run Python analysis on MATSim simulation output. Use when: 'analyze output', 'analyze results', 'run Python analysis', 'show mode share', 'show scores', 'analyze LLM decisions', 'launch notebook'."
argument-hint: "[output-dir-or-action]"
allowed-tools: ["Bash(conda *)", "Bash(python3 *)", "Bash(python *)", "Bash(pip *)", "Bash(jupyter *)", "Read"]
---

# MATSim Python Analysis

Run analysis using the `matsim-py-analysis` package (conda env: `matsim-ai`).

## Setup check

First verify the environment:
```bash
eval "$(conda shell.bash hook 2>/dev/null)" && conda activate matsim-ai && python3 -c "import matsim_py_analysis; print(f'v{matsim_py_analysis.__version__} OK')"
```

If not installed:
```bash
eval "$(conda shell.bash hook 2>/dev/null)" && conda activate matsim-ai && pip install -e ./matsim-py-analysis
```

## Default output directory

If no directory specified, use `output/siouxfalls-llm-agents/`.

## Available analyses

All commands must be prefixed with `eval "$(conda shell.bash hook 2>/dev/null)" && conda activate matsim-ai &&`

### Quick summary
```bash
matsim-analyze analyze <output-dir>
```

### Reasoning & bottlenecks (regex-based, fast)
```bash
matsim-analyze reasoning <output-dir>      # reasoning categories, tool demand
matsim-analyze bottlenecks <output-dir>    # failure classification, tool gap
matsim-analyze compare <dir-a> <dir-b>     # A/B comparison of two runs
```

### NLP pipeline (Phase 6 — LLM-as-judge via local Ollama)

Requires Ollama running (`ollama serve`). Pass `--no-llm` for regex fallback.

```bash
# Classify reasoning traces into decision categories
matsim-analyze classify <output-dir>              # LLM-as-judge (slow, accurate)
matsim-analyze classify --no-llm <output-dir>     # regex fallback (fast)

# Score plan satisfaction from reasoning
matsim-analyze sentiment <output-dir>
matsim-analyze sentiment --no-llm <output-dir>

# Mine decision patterns + cluster using embeddings
matsim-analyze decisions <output-dir>             # uses nomic-embed-text + kmeans
matsim-analyze decisions --no-embeddings <output-dir>

# Full feedback loop: NLP metrics <-> MATSim scores
matsim-analyze feedback <output-dir>              # correlations, decision predictability, realism
matsim-analyze feedback --no-llm <output-dir>     # fast regex-only mode
```

The `feedback` command is the money-maker for research — it joins NLP classifications with MATSim scores, computes decision predictability via Random Forest, and validates physical realism (car constraints, PT chain handling, route grounding).

### Specific analyses (via Python)

**Mode share evolution:**
```python
from matsim_py_analysis.analysis.mode_share import plot_mode_share_evolution
fig = plot_mode_share_evolution("<output-dir>/modestats.csv")
fig.savefig("mode_share.png", dpi=150, bbox_inches="tight")
```

**Score convergence:**
```python
from matsim_py_analysis.analysis.scores import plot_score_convergence
fig = plot_score_convergence("<output-dir>/scorestats.csv")
fig.savefig("scores.png", dpi=150, bbox_inches="tight")
```

**LLM decisions:**
```python
from matsim_py_analysis.analysis.llm_decisions import analyze_mode_changes, identify_slow_conversations
from matsim_py_analysis.parsers.chat_log import parse_chat_log
entries = parse_chat_log("<output-dir>/llm_chat_log_ChatLog_combined.jsonl")
changes = analyze_mode_changes(entries)
slow = identify_slow_conversations(entries, threshold_seconds=30)
```

### Launch notebook
```bash
jupyter notebook matsim-py-analysis/notebooks/
```

## Important notes

- Always activate conda env `matsim-ai` before running Python commands
- MATSim CSVs use semicolon delimiter (`;`), except `llm_person_stats_combined.csv` which uses comma
- Time values in CSVs are `HH:MM:SS` strings — parsers convert to seconds automatically
- JSONL chat logs have nested JSON (requestBody/responseBody are escaped strings)
