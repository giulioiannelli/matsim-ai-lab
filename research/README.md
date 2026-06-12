# MATSim AI Lab — Knowledge Base

## Core question

Can LLM-powered agents make physically plausible, behaviorally realistic travel decisions within a MATSim transport simulation — and what emergent mobility patterns arise from their reasoning?

## Guides

Start here if you're new to the project or to MATSim.

| Guide | For whom | What you'll learn |
|-------|----------|-------------------|
| [01 — MATSim Fundamentals](guides/01-matsim-fundamentals/) | New to MATSim | The iteration loop, config, scoring, events, replanning |
| [02 — Plugin Development Patterns](guides/02-matsim-plugin-patterns/) | Want to write Java plugins | Guice DI, custom strategies, event handlers, config groups |
| [03 — LLM Plugin Architecture](guides/03-llm-plugin-architecture/) | Working on the LLM integration | Chat pipeline, tools, DTOs, RAG, prompts, execution modes |
| [04 — Running a Simulation](guides/04-running-a-simulation/) | Want to run experiments | Prerequisites, config reference, output file map |
| [05 — Python Analysis](guides/05-python-analysis/) | Analyzing outputs | How matsim-py-analysis connects to simulation outputs |

See [guides/README.md](guides/README.md) for a recommended reading order.

## Research directions

Active threads exploring where this project is going.

| Thread | Focus |
|--------|-------|
| [Meaningful Reasoning Plan](directions/meaningful-reasoning-plan.md) | **Active** — move from mechanical template-filling to substantive reasoning via comparison tools, self-questioning prompts, persona memory, and reasoning models within 16 GB VRAM |
| [Agent Replanning Quality](directions/agent-replanning.md) | Prompt design, tool accuracy, plan quality metrics |
| [Agent Reasoning Analysis](directions/agent-reasoning-analysis.md) | NLP on reasoning traces, sentiment, decision patterns |
| [Infrastructure & Scalability](directions/infrastructure-scalability.md) | Scaling from 4 agents to population-scale |
| [Python Analysis](directions/python-analysis.md) | Data pipeline, visualization, cross-run comparison |

See [directions/README.md](directions/README.md) for the full thread listing.

## Project components

| Component | Location | Language | Purpose |
|-----------|----------|----------|---------|
| MATSim AI Lab | `src/main/java/` | Java | Vendored LLM plugin + scenario runners |
| matsim_llm_plugins | `matsim_llm_plugins/` | Java | Upstream plugin repo (forked) |
| matsim-py-analysis | `matsim-py-analysis/` | Python | Log parsing, NLP, visualization |
| Simulation output | `output/` | Data | Plans, events, chat logs, stats |
