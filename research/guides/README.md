# Guides — Reading Order

These guides are designed for someone who knows Python but is learning Java and MATSim. Each section builds on the previous, but any guide can be read standalone.

## Recommended path

### If you want to run experiments now
Start with **04** (running a simulation), then read **03** (LLM architecture) when you want to understand or modify the LLM integration.

### If you want to understand the full stack
Read in order: **01** → **02** → **03** → **04** → **05**.

## Guide index

1. **[MATSim Fundamentals](01-matsim-fundamentals/)** — The iteration loop, config, scoring, events, replanning. What MATSim does and how its pieces fit together.

2. **[Plugin Development Patterns](02-matsim-plugin-patterns/)** — How to extend MATSim with custom modules. Guice dependency injection explained for Python developers. Walks through the actual code in this project.

3. **[LLM Plugin Architecture](03-llm-plugin-architecture/)** — The heart of this project: how the LLM connects to MATSim, the chat pipeline, tool-calling framework, plan DTOs, RAG memory, and the three execution modes.

4. **[Running a Simulation](04-running-a-simulation/)** — Practical: prerequisites, service startup, config parameters, what output files mean.

5. **[Python Analysis](05-python-analysis/)** — How the `matsim-py-analysis` submodule connects to simulation outputs for data science.
