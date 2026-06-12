# Python Analysis Submodule

## Purpose

A Python submodule for data manipulation, visualization, and NLP analysis of MATSim + LLM simulation outputs. Java is the right tool for the simulation loop; Python is the right tool for data science.

## Scope

### Phase 1: Log parsing and basic metrics
- Parse `llm_chat_log_ChatLog_combined.jsonl` into pandas DataFrames
- Extract: timing per agent, token counts, tool calling sequences, reasoning text
- Basic plots: time distribution, tokens vs plan complexity, tool accuracy rates

### Phase 2: Plan comparison
- Parse MATSim plans XML (before/after LLM modification)
- Diff plans: what did the LLM change? Mode switches, timing adjustments, route changes
- Visualize on network: which links/facilities are affected by LLM decisions

### Phase 3: Reasoning NLP
- Extract reasoning traces from `responseBody.choices[].message.reasoning`
- Sentiment analysis (VADER, TextBlob, or small transformer)
- Topic extraction: what concepts does the LLM mention? (congestion, time, comfort, cost)
- Decision pattern classification: conservative (no change), mode switcher, time adjuster
- Uncertainty detection: does the LLM hedge? ("might", "probably", "not sure")

### Phase 4: Cross-iteration dynamics
- Track individual agents across iterations: does score improve?
- Mode share evolution: MATSim global mode split per iteration
- Convergence analysis: do LLM agents stabilize or keep changing plans?

### Phase 5: Comparison dashboards
- LLM agents vs heuristic agents: score trajectories, mode choices
- Interactive Plotly/Dash dashboards for exploring results

## Technical setup
- Python 3.11+ with venv or conda
- Core deps: pandas, matplotlib, seaborn, plotly
- NLP deps: nltk/VADER, transformers (small model for classification)
- MATSim parsing: matsim-tools or custom XML parser
- Jupyter notebooks for exploration, scripts for reproducible analysis

## Integration with Java side
- Java simulation writes to `output/<scenario>/`
- Python reads from same directory
- No runtime coupling — Python runs post-hoc on completed simulation outputs
- Future: live monitoring via watching JSONL file growth

## Directory structure (proposed)
```
analysis/                   # or python/ or notebooks/
├── pyproject.toml
├── src/
│   ├── log_parser.py      # JSONL → DataFrame
│   ├── plan_diff.py       # Compare before/after plans
│   ├── reasoning_nlp.py   # NLP on reasoning traces
│   └── viz.py             # Plotting utilities
├── notebooks/
│   ├── 01_basic_stats.ipynb
│   ├── 02_reasoning_analysis.ipynb
│   └── 03_cross_iteration.ipynb
└── tests/
```
