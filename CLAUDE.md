# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A research laboratory for integrating LLM-powered agents into MATSim transport simulations. Standard MATSim agents follow rule-based replanning (best-score, re-route); here, selected agents instead have their daily plans sent to an LLM which reasons about mode choices, calls routing tools, and returns modified plans via structured tool calling.

The LLM integration code originates from [matsim_llm_plugins](https://github.com/auzpatwary37/matsim_llm_plugins) and is vendored here with fixes for MATSim 2025.0. The upstream plugin repo is checked out at `./matsim_llm_plugins/` (forked to giulioiannelli/matsim_llm_plugins, upstream = auzpatwary37). See `matsim_llm_plugins/CONTRIBUTING.md` for the git workflow.

## Repository structure

```
matsim-ai-lab/
├── src/main/java/          # Vendored plugin code + project runners
├── matsim_llm_plugins/     # Upstream plugin repo (gitignored, own git history)
├── output/                 # Simulation outputs (gitignored)
├── research/               # Research directions, experiment notes, analysis plans
├── .claude/diary/          # Session-by-session work logs for continuity
└── scenarios/              # Custom scenario data
```

## Build and run commands

```bash
# Compile (fastest feedback loop)
./mvnw compile

# Build fat JAR (~210 MB, skip tests)
./mvnw clean package -DskipTests

# Run unit test (equil scenario, 1 iteration, validates against reference output)
./mvnw test -Dtest=RunMatsimTest

# Sioux Falls with LLM agent replanning (main integration scenario)
# Args: [iterations] [modelName]
./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.RunSiouxFallsLLMAgents" -Dexec.args="20 qwen3.5"

# Sioux Falls baseline (no LLM)
./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.RunSiouxFalls" -Dexec.args="100"

# Kelheim multimodal (PT, DRT, bike)
./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.RunKelheim" -Dexec.args="50"
```

### Prerequisites

- Java 21+
- Ollama running locally with models pulled: `ollama pull qwen3.5` and `ollama pull nomic-embed-text`
- Qdrant vector DB: `sudo docker run -d -p 6333:6333 -p 6334:6334 qdrant/qdrant`

## Architecture

### Simulation flow with LLM replanning (ConnectionType.replanning)

```
MATSim iteration loop
  └─ Replanning phase: LLMReplanningStrategyModule fires
       ├─ Select N AI agents (configured via numberOfAIAgents)
       ├─ For each agent:
       │    ├─ Serialize Plan → PlanDTO (JSON: activities + legs + routes)
       │    ├─ Send to LLM with system prompt (IndividualPrompt)
       │    ├─ LLM reasons about plan, can call tools:
       │    │    ├─ router_tool → MATSim TripRouter (real tool, response goes back to LLM)
       │    │    ├─ pull_additional_context → Qdrant RAG (real tool)
       │    │    └─ extract_plan → final plan extraction (dummy tool, ends conversation)
       │    ├─ Validate plan via ExternalValidator
       │    └─ Replace agent's plan with LLM-modified version
       └─ MATSim scores plans; ExpBetaPlanSelector keeps/discards based on score
```

### Three execution modes

| Mode | ConnectionType | Who has final word | Description |
|------|---------------|-------------------|-------------|
| MATSim → LLM → MATSim | `replanning` | MATSim scoring | LLM is one strategy among many; plans compete on score |
| LLM final word | `controllerlistener` | LLM | Modifies plans before mobsim, bypasses scoring |
| Real-time LLM | `withinday` | LLM | Hooks into QSim, modifies plans during simulation |

### Key packages and their roles

| Package | Role |
|---------|------|
| `org.matsim.project` | Entry points (RunSiouxFallsLLMAgents, RunSiouxFalls, etc.) |
| `matsimBinding` | MATSim↔LLM integration: `LLMConfigGroup`, `LLMIntegrationModule`, `LLMReplanningStrategyModule`, `AgentExperienceEventHandlerV2` |
| `chatcommons` | `ChatCompletionClientImpl` (OkHttp→LLM, 10min timeout), `DefaultChatManager` (multi-turn tool-calling loop with stats) |
| `chatrequest` / `chatresponse` | OpenAI-format request/response serialization (works with Ollama, LM Studio, OpenAI) |
| `matsimdtobjects` | `PlanDTO`/`ActivityDTO`/`LegDTO`/route DTOs — bidirectional MATSim Plan ↔ JSON with validation |
| `gsonprocessor` | Legacy plan serialization (PlanGson). Being superseded by matsimdtobjects |
| `tools` | Tool-calling framework: `ITool`, `DefaultToolManager`, `ExternalValidator`, DTO system |
| `tools.Implement` | Concrete tools: `ExtractPlanTool`, `RouterTool`, `PullAdditionalContextTool` |
| `prompts` | `IndividualPrompt` — system prompts and plan extraction prompts for the LLM |
| `rag` | Qdrant vector DB integration (`VectorDBImplement`) for agent memory / RAG |
| `run` | Upstream CLI runner (`Run.java` with picocli) |

### Tool-calling pattern

Tools implement `ITool<T>`. The distinction between tool types matters:
- **Dummy tools** (`ExtractPlanTool`): output consumed by MATSim only, not sent back to LLM — the conversation ends
- **Real tools** (`RouterTool`, `PullAdditionalContextTool`): response sent back to LLM for further reasoning, enabling multi-turn tool use

### Plan serialization (PlanDTO)

`PlanDTO.toDTOFromBaseObject()` converts a MATSim Plan to JSON. Activities include type, endTime, facilityId/linkId. Legs include mode, routingMode, departureTime, travelTime, and full route details (NetworkRouteDTO for car/bike, TransitPassengerRouteDTO for PT, GenericRouteDTO for walk). The LLM modifies this JSON and returns it via `extract_plan`.

## Critical implementation details

**Ollama backend workaround**: Use `BackendType.LM_STUDIO` pointed at Ollama's OpenAI-compatible endpoint (`localhost:11434/v1/chat/completions`) with authorization `"ollama"`.

**HTTP timeouts**: OkHttp read timeout is 10 minutes (multi-turn tool calling can be slow). Connect and write timeouts are 30s.

**Error handling**: `LLMReplanningStrategyModule.finishReplanning()` wraps each agent's LLM call in try-catch — a timeout or bad tool name skips that agent and keeps the original plan.

**Dependency pins** (to satisfy MATSim parent enforcer):
- jackson 2.21, grpc-bom 1.77, guava 33.4.8-jre, slf4j 2.0.17, error_prone 2.36, protobuf-java 4.29.3
- protobuf-javalite excluded from bicycle contrib (conflicts with protobuf-java)
- log4j → log4j2 in LLMReplanningStrategyModule and LLMWithinDayListener

**Conversation logs**: Written to `llm_chat_log_ChatLog_combined.jsonl` in the output directory. Each line is a JSON object with full request/response bodies, timing, token counts, and reasoning traces.

## Output structure

All simulation output goes to `output/<scenario-name>/` (gitignored). Key files:
- `llm_chat_log_ChatLog_combined.jsonl` — full LLM conversation logs
- `llm_person_stats_combined.csv` — per-agent LLM replanning stats
- `ITERS/it.N/` — per-iteration plans and events
- `logfile.log` — MATSim simulation log
- `dashboard-*.yaml` — SimWrapper visualization dashboards

## Visualization

- **SimWrapper** (recommended): `simwrapper here` in an output directory, or https://simwrapper.app
- **Simunto Via**: professional desktop app from https://www.simunto.com/via/

## GPU setup (development machine)

- RTX 3080 Laptop 16GB VRAM
- Models tested: qwen3.5 (9.7B, best balance), qwen3:14b (too slow), gemma4:e4b (hallucinates tool names), qwen2.5:7b (untested)
- Embedding: nomic-embed-text (274MB)

## Session continuity

Work logs are kept in `.claude/diary/YYYY-MM-DD.md`. Each session records what was done, decisions made, and next steps. Read the latest diary entry at the start of each session for context.

Research directions and analysis plans are in `research/`.

### Planning & continuity are local-first

All plans and durable project info live **in-repo and committed**, never only in
global memory or the global `~/.claude/plans/` directory (which mixes projects
and can be washed out). Authoritative locations:

- **Master roadmap & plans** → `.claude/plans/` (start at
  `.claude/plans/persona-replanning-roadmap.md`).
- **Session logs** → `.claude/diary/YYYY-MM-DD.md`.
- **Research / guides** → `research/`.

The global memory store may *point* to these files, but the content of record is
here in the repo.

## Tool design workflow (feedback loop)

The central workflow of this project is iterative tool design for LLM agents:

```
Run simulation → Analyze outputs → Design tools/prompts → Implement → Run again
```

### Custom Claude Code commands

- `/analyze-run [output-dir]` — Parse JSONL logs, report bottlenecks, suggest tools
- `/design-tool [description]` — Generate Java ITool implementation from natural language
- `/compare-runs [dir1] [dir2]` — Diff two runs to measure tool/prompt impact
- `/prompt-version [action]` — Manage versioned prompts (list, create, diff, show)

### Python analysis (matsim-py-analysis/)

```bash
# Always use the matsim-ai conda environment
conda run -n matsim-ai matsim-analyze analyze output/siouxfalls-llm-agents/
conda run -n matsim-ai matsim-analyze reasoning output/siouxfalls-llm-agents/
conda run -n matsim-ai matsim-analyze bottlenecks output/siouxfalls-llm-agents/
conda run -n matsim-ai matsim-analyze tool-usage output/siouxfalls-llm-agents/
conda run -n matsim-ai matsim-analyze persona output/siouxfalls-llm-agents/
conda run -n matsim-ai matsim-analyze compare output/run-a/ output/run-b/
```

The parsers handle both LLM wire formats (OpenAI-compat `choices[].message.reasoning`
and Ollama-native `message.thinking`). Conversations without the legacy `You are
person XXXX` marker (i.e. persona prompts) are keyed by a stable hash of the
original-plan JSON; ground-truth person ids are in `llm_person_stats_combined.csv`.

Key analysis modules (under `matsim-py-analysis/src/matsim_py_analysis/`):
- `analysis/` — reasoning categorization, bottlenecks, run comparison, persona signals
- `analysis/persona.py` — persona emergence (first-person + trait grounding), degenerate repetition loops, persona↔tool contradictions (`matsim-analyze persona`)
- `tool_usage/` — tool call/response extraction, hallucination & anomaly detection (see its README)
- `conversations/` — human-readable chat transcripts; `matsim-analyze chat <out-dir>` (see its README)
- `parsers/` — JSONL chat log + MATSim CSV readers

### New tool checklist

When creating a new Java tool for the LLM plugin:

1. Create class in `src/main/java/tools/Implement/` implementing `ITool<T>`
2. Register arguments in constructor using `SimpleStringDTO`/`SimpleDoubleDTO`/etc.
3. Implement: `getName()`, `getDescription()`, `isDummy()`, `getOutputClass()`
4. Implement `callTool()` — extract args, get context objects, compute, return `DefaultToolResponse`
5. Implement `verifyArguments()` — validate inputs, check context
6. Register in `LLMIntegrationModule.install()`: `toolManager.registerTool(new MyTool())`
7. Update system prompt to tell the LLM about the new tool
8. Compile: `./mvnw compile`
9. Run simulation and verify tool appears in JSONL logs

Reference implementation: `RouterTool.java` (real tool), `ExtractPlanTool.java` (dummy tool).

### Proposed tools (from bottleneck analysis)

| Tool | Purpose | Priority | Status |
|------|---------|----------|--------|
| `available_modes` | Pre-compute which modes person can use at each location | HIGH | Planned |
| `activity_chain_summary` | Simplified plan view without PT chain noise | HIGH | Planned |
| `validate_timing` | Check temporal consistency before extract_plan | MEDIUM | Planned |
| `persona_memory` | Read accumulated behavioral patterns from Qdrant | MEDIUM | Planned |
| `update_persona_memory` | Write back persona after planning (dummy) | MEDIUM | Planned |

## Project goals / research direction

See `research/README.md` for the full research roadmap. Key areas:
- **LLM-powered replanning**: getting AI agents to make physically plausible travel decisions
- **Prompt engineering**: minimizing reasoning time while maintaining plan quality
- **I/O control**: full capture and analysis of LLM reasoning traces (sentiment, decision patterns)
- **Scalability**: from 4 agents to population-scale, with memory/caching optimizations
- **Python analysis submodule**: data manipulation and visualization of simulation + LLM outputs
- **Persona memory**: accumulated structured memory as a speedup mechanism, transfer learning across scenarios
