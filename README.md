# matsim-ai-lab

Development laboratory for integrating LLM-powered agents into [MATSim](https://matsim.org/) transport simulations.

This project replaces standard MATSim agents with AI agents driven by Large Language Models. Where traditional agents follow rule-based replanning strategies (best-score, re-route), LLM agents analyze their daily travel plans and make mode-choice decisions through natural language reasoning, calling back into the simulation via a tool-calling framework.

The LLM integration layer lives in a companion repository ([matsim_llm_plugins](https://github.com/auzpatwary37/matsim_llm_plugins)), whose source is vendored here for active development against MATSim 2025.0.

## How it works

```
MATSim Simulation Loop
  │
  ├─ Standard iteration: mobsim → scoring → replanning
  │
  └─ Every N iterations: LLMReplanningListener triggers
       │
       ├─ Select K agents from the population
       ├─ Serialize each agent's plan to JSON (activities + legs)
       ├─ Send to LLM with system prompt describing the scenario
       ├─ LLM reasons about the plan and calls dummy_plan tool
       ├─ Deserialize modified plan back into MATSim
       └─ Agent uses the LLM-modified plan in subsequent iterations
```

The LLM communicates via the OpenAI-compatible chat completions API (Ollama, LM Studio, or OpenAI directly). Plans are exchanged as JSON through a function-calling interface: the LLM receives the current plan and returns a modified version by invoking the `dummy_plan` tool.

## Project structure

```
matsim-ai-lab/
├── src/main/java/
│   ├── org/matsim/project/          # Entry points
│   │   ├── RunMatsim.java           # Standard MATSim (baseline)
│   │   ├── RunSiouxFallsLLMAgents.java  # Full LLM replanning
│   │   ├── RunSiouxFallsWithLLM.java    # LLM post-query test
│   │   ├── TestLLMConnection.java       # Connectivity check
│   │   └── llm/                     # LLM-MATSim glue
│   │       ├── CreatePlanTool.java       # Tool the LLM calls to modify plans
│   │       ├── LLMReplanningListener.java # Iteration callback
│   │       └── LLMReplanningModule.java  # Guice registration
│   ├── chatcommons/                 # Chat session management, HTTP client
│   ├── chatrequest/                 # Request serialization (OpenAI format)
│   ├── chatresponse/                # Response parsing
│   ├── gsonprocessor/               # MATSim Plan <-> JSON conversion
│   ├── matsimBinding/               # LLMConfigGroup + Guice module
│   ├── tools/                       # Generic tool-calling framework
│   └── rag/                         # ChromaDB vector DB integration (optional)
├── scenarios/equil/                 # Minimal test scenario (100 agents)
├── pom.xml                          # MATSim 2025.0, Java 21
└── SETUP_GUIDE.md                   # Detailed setup and usage instructions
```

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java (OpenJDK) | 21+ | Build and run |
| Maven | 3.8+ (wrapper included) | Dependency management |
| Ollama | latest | Local LLM inference |
| Docker | latest | ChromaDB for RAG (optional) |

## Quick start

```bash
# 1. Clone
git clone https://github.com/giulioiannelli/matsim-ai-lab.git
cd matsim-ai-lab

# 2. Pull an LLM model
ollama pull qwen2.5:7b

# 3. Test the LLM connection
./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.TestLLMConnection"

# 4. Run baseline MATSim (no LLM, equil scenario)
./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.RunMatsim"

# 5. Run Sioux Falls with LLM-powered agent replanning
./mvnw -q compile exec:java \
  -Dexec.mainClass="org.matsim.project.RunSiouxFallsLLMAgents" \
  -Dexec.args="20"
```

## Available scenarios

| Entry point | Scenario | What it does |
|------------|----------|-------------|
| `RunMatsim` | equil (100 agents) | Baseline MATSim run, no LLM |
| `RunSiouxFalls` | Sioux Falls (1400+ agents) | Baseline benchmark scenario |
| `RunSiouxFallsLLMAgents` | Sioux Falls + LLM | LLM replans 5 agents every 5 iterations |
| `RunSiouxFallsWithLLM` | Sioux Falls + LLM query | Post-simulation LLM test |
| `RunKelheim` | Kelheim (multimodal) | German town with PT and DRT |
| `TestLLMConnection` | -- | Sends a test message to the LLM backend |

## LLM configuration

Configuration is managed through `LLMConfigGroup` (in `matsimBinding/`). Key parameters:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `backendType` | LM_STUDIO | Backend API format (LM_STUDIO, OPENAI, OLLAMA) |
| `llmHost` | localhost | LLM server host |
| `llmPort` | 11434 | LLM server port (11434 for Ollama) |
| `modelName` | qwen2.5:7b | Model to use |
| `temperature` | 0.7 | Sampling temperature |
| `maxTokens` | 2048 | Max response tokens |
| `enableContextRetrieval` | false | Enable ChromaDB RAG |

## Relation to matsim_llm_plugins

The [matsim_llm_plugins](https://github.com/auzpatwary37/matsim_llm_plugins) repository provides the core LLM infrastructure (chat client, tool framework, config, RAG). That plugin targets an older MATSim version, so its source is vendored into this project with fixes applied for MATSim 2025.0 compatibility. Changes made here may eventually be contributed back upstream.

## Tech stack

- **MATSim 2025.0** -- agent-based transport simulation
- **Java 21** -- language runtime
- **OkHttp 4.12.0** -- HTTP client for LLM API calls
- **GSON 2.11.0** -- JSON serialization of MATSim plans
- **ChromaDB 0.1.7** -- vector database for RAG (optional)
- **Ollama** -- local LLM inference server

## License

MATSim program code is distributed under the [GNU GPLv2](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html). Input/output data files are licensed under [CC BY 4.0](http://creativecommons.org/licenses/by/4.0/).
