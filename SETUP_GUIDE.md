# MATSim AI Lab - Setup & Usage Guide

## Table of Contents
1. [Prerequisites](#1-prerequisites)
2. [Project Setup](#2-project-setup)
3. [Building the Project](#3-building-the-project)
4. [Running Scenarios](#4-running-scenarios)
5. [Understanding the Output](#5-understanding-the-output)
6. [Visualization](#6-visualization)
7. [LLM Plugin Integration](#7-llm-plugin-integration)
8. [Available Scenarios](#8-available-scenarios)
9. [Key Files Reference](#9-key-files-reference)

---

## 1. Prerequisites

| Tool | Version | Check command |
|------|---------|---------------|
| Java (OpenJDK) | 21+ | `java -version` |
| Maven | 3.8+ (via wrapper) | `./mvnw --version` |
| Ollama (for LLM) | 0.17+ | `ollama --version` |
| Docker (for Qdrant vector DB) | 29+ | `docker --version` |

---

## 2. Project Setup

### 2.1 Clone and verify

```bash
git clone https://github.com/matsim-org/matsim-ai-lab.git
cd matsim-ai-lab
```

### 2.2 Project structure

```
matsim-ai-lab/
  pom.xml                          # Maven config (MATSim 2025.0, Java 21)
  mvnw / mvnw.cmd                  # Maven wrapper (no global Maven install needed)
  scenarios/equil/                  # Default scenario
    config.xml                     # Simulation configuration
    network.xml                    # Road network (15 nodes, 23 links)
    plans100.xml                   # 100 agents with home-work-home plans
  src/main/java/org/matsim/project/
    RunMatsim.java                 # Main entry point (loads config, runs simulation)
    RunMatsimApplication.java      # Alternative entry using MATSimApplication framework
    RunMatsimFromExamplesUtils.java# Loads scenarios from matsim-examples JAR
  src/main/java/org/matsim/gui/
    MATSimGUI.java                 # GUI launcher (Main-Class in the JAR)
```

### 2.3 Key configuration: pom.xml

- **Parent POM**: `org.matsim:matsim-all:2025.0` (inherits MATSim dependency management)
- **Java**: 21 (`maven.compiler.release=21`)
- **Core dependency**: `org.matsim:matsim:2025.0`
- **Contribs included**: otfvis, simwrapper, noise, roadpricing, taxi, av, freight, bicycle, emissions, application, vsp, minibus
- **Build**: maven-shade-plugin creates a single fat JAR (~210 MB) with all dependencies

### 2.4 LLM Plugin integration (what was done)

The LLM plugin from https://github.com/auzpatwary37/matsim_llm_plugins (May2025 branch) targets MATSim 15.0 (an older version), so it cannot be used as a Maven dependency directly. It was integrated by copying source code:

**Source packages copied into `src/main/java/`:**
- `chatcommons/` - Chat management, HTTP client for LLM communication
- `chatrequest/` - Request serialization (OpenAI/LM Studio format)
- `chatresponse/` - Response parsing
- `tools/` - Tool-calling framework (function calling for LLM)
- `rag/` - ChromaDB vector database integration for RAG
- `matsimBinding/` - MATSim Guice module and config group (`LLMConfigGroup`, `LLMIntegrationModule`)

**Fixes applied to the copied source:**
1. Removed dead `import apikeys.APIKeys` from `ChatCompletionClientImpl.java`
2. Added null guard in `VectorDBImplement.java` for when no RAG source file is configured
3. Guarded `VectorDbProvider` in `LLMIntegrationModule.java` to return null when RAG is disabled
4. Increased OkHttp timeout from 10s to 120s for local model inference

**Dependencies added to `pom.xml`:**
```xml
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>okhttp</artifactId>
    <version>4.12.0</version>
</dependency>
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>2.11.0</version>
</dependency>
<dependency>
    <groupId>io.github.amikos-tech</groupId>
    <artifactId>chromadb-java-client</artifactId>
    <version>0.1.7</version>
    <!-- with exclusions for onnxruntime, djl, joda-time -->
</dependency>
<!-- Kotlin stdlib pins to resolve enforcer conflicts -->
<dependency>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>kotlin-stdlib</artifactId>
    <version>1.9.10</version>
</dependency>
```

---

## 3. Building the Project

### Full build (compile + test + package JAR)
```bash
./mvnw clean package
```

### Build without tests (faster)
```bash
./mvnw clean package -DskipTests
```

### Compile only (no JAR, fastest)
```bash
./mvnw compile
```

### Run the unit test
```bash
./mvnw test -Dtest=RunMatsimTest
```
This runs the equil scenario for 1 iteration and validates output against reference files in `test/input/`.

---

## 4. Running Scenarios

### 4.1 Standard run (equil scenario, no visualization)

```bash
# Option A: via Maven (compiles first)
./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.RunMatsim"

# Option B: via the fat JAR (must build first)
java -cp matsim-ai-lab-0.0.1-SNAPSHOT.jar org.matsim.project.RunMatsim
```

This runs `scenarios/equil/config.xml`: 100 agents, 10 iterations, output to `./output/`.

### 4.2 Run with a custom config file

```bash
./mvnw -q compile exec:java \
  -Dexec.mainClass="org.matsim.project.RunMatsim" \
  -Dexec.args="path/to/your/config.xml"
```

### 4.3 Run a scenario from matsim-examples JAR

Edit `RunMatsimFromExamplesUtils.java` and change the scenario name:
```java
URL context = ExamplesUtils.getTestScenarioURL("kelheim");  // or "siouxfalls-2014", "berlin"
```
Then:
```bash
./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.RunMatsimFromExamplesUtils"
```

### 4.4 Run with LLM-powered agent replanning

This is the main integration scenario: selected agents have their plans modified by an LLM during MATSim's replanning phase.

**Step 1 — Start infrastructure:**
```bash
# Ollama (should already be running)
ollama pull qwen3.5            # 9.7B, recommended model
ollama pull nomic-embed-text   # embedding model for Qdrant RAG
ollama list                    # verify models available
curl http://localhost:11434/v1/models  # verify API serving

# Qdrant vector DB
sudo systemctl start docker
sudo docker run -d -p 6333:6333 -p 6334:6334 qdrant/qdrant
curl http://localhost:6333/healthz  # should print "healthz check passed"
```

**Step 2 — Run:**
```bash
# Args: [iterations] [modelName]
./mvnw -q compile exec:java \
  -Dexec.mainClass="org.matsim.project.RunSiouxFallsLLMAgents" \
  -Dexec.args="10 qwen3.5"
```

**What happens:**
1. Loads Sioux Falls 2014 scenario (84k agents, PT network)
2. Selects 5 agents as "AI agents" controlled by the LLM
3. Each iteration: MATSim mobsim runs, then LLM replanning fires
4. For each AI agent: plan serialized → sent to LLM → LLM reasons + calls tools (routing, RAG) → returns modified plan
5. MATSim scores all plans; ExpBetaPlanSelector keeps/discards LLM plans based on score
6. Output written to `./output/siouxfalls-llm-agents/`

**Configuration** (edit `RunSiouxFallsLLMAgents.java` to change):
| Parameter | Default | Description |
|-----------|---------|-------------|
| AI agents | 5 | Number of LLM-controlled agents |
| Max tokens | 4096 | Limits LLM reasoning length |
| Temperature | 0.3 | Lower = more deterministic |
| Max tool iterations | 10 | Cap on tool-calling rounds |
| Strategy weight | 1.0 | Weight relative to other MATSim strategies |

**Model performance** (RTX 3080 Laptop, 16GB VRAM):
| Model | Avg time/call | Tool calling | Recommendation |
|-------|--------------|-------------|----------------|
| qwen3.5 (9.7B) | 20s | Good | Recommended |
| qwen3:14b | 244s | Good | Too slow |
| gemma4:e4b | 75s | Bad (hallucinates names) | Not recommended |
| qwen2.5:7b | untested | Expected good | Try for speed |

**Key output files:**
| File | Description |
|------|-------------|
| `llm_chat_log_ChatLog_combined.jsonl` | Full LLM conversations (prompts, responses, reasoning, timing) |
| `llm_person_stats_combined.csv` | Per-agent replanning stats |
| `scorestats.csv` | Score evolution across iterations |
| `modestats.csv` | Mode share per iteration |

---

## 5. Understanding the Output

After a simulation run, the `./output/` directory contains:

### Final state files
| File | Description |
|------|-------------|
| `output_plans.xml.gz` | Final agent plans with scores (selected routes after all iterations) |
| `output_events.xml.gz` | Every event in the last iteration (departures, arrivals, link traversals) |
| `output_network.xml.gz` | Network used in the simulation |
| `output_trips.csv.gz` | Trip-level data: origin, destination, mode, travel time, distance |
| `output_legs.csv.gz` | Leg-level data: route details, distance, duration |
| `output_persons.csv.gz` | Per-agent summary with final executed score |
| `output_config.xml` | Full resolved config (all defaults filled in) |

### Convergence & statistics
| File | Description |
|------|-------------|
| `scorestats.csv` | Average scores per iteration (executed, worst, average, best) |
| `modestats.csv` | Mode share per iteration |
| `modestats.png` | Mode share line chart |
| `modestats_stackedbar.png` | Mode share stacked bar chart |
| `stopwatch.csv` | Wall-clock time per iteration step |

### Per-iteration data
| File | Description |
|------|-------------|
| `ITERS/it.N/N.events.xml.gz` | Events for iteration N |
| `ITERS/it.N/N.legHistogram*.png` | Departure/arrival time distribution charts |
| `ITERS/it.N/N.trips.csv.gz` | Trip data for iteration N |

### Quick inspection commands
```bash
# View trip summary (first 10 rows)
zcat output/output_trips.csv.gz | head -10

# View score convergence
cat output/scorestats.csv

# View per-agent scores
zcat output/output_persons.csv.gz | head -10

# Count events
zcat output/output_events.xml.gz | wc -l

# Open charts
xdg-open output/modestats.png
xdg-open output/ITERS/it.10/10.legHistogram_all.png
```

### About the equil scenario results
The equil scenario is intentionally trivial: 100 agents, all using cars, on a simple bottleneck network with 9 alternative routes. There is minimal convergence because the scenario reaches near-equilibrium quickly. All agents use car mode because no other modes are configured. This is by design — it's a test/tutorial scenario, not a realistic one.

---

## 6. Visualization

### 6.1 SimWrapper (web-based, recommended)

SimWrapper is the modern, actively maintained visualization platform.

**Option A: Enable auto-generated dashboards (requires re-run)**

Uncomment in `RunMatsim.java`:
```java
import org.matsim.simwrapper.SimWrapperModule;
// ...
controler.addOverridingModule( new SimWrapperModule() );
```
Re-run the simulation. Then open https://simwrapper.app in Chrome/Edge, click "Add Local Folder", and select the `output/` directory.

**Option B: View existing output (no re-run needed)**

1. Open Chrome or Edge
2. Go to https://simwrapper.app
3. Click "Add Local Folder"
4. Select the `output/` directory
5. Browse files — SimWrapper auto-detects events, network, and CSV files

**Option C: Python CLI (works in any browser)**
```bash
pip install simwrapper
cd output/
simwrapper here
```

### 6.2 OTFVis (real-time OpenGL viewer)

OTFVis shows vehicles moving on the network in real-time during simulation.

**Enable live visualization (requires re-run):**

Uncomment in `RunMatsim.java`:
```java
import org.matsim.contrib.otfvis.OTFVisLiveModule;
// ...
controler.addOverridingModule( new OTFVisLiveModule() );
```
A window opens when you run the simulation. Controls: mouse wheel to zoom, drag to pan, slider for speed.

**Convert existing output to playable MVI file:**
```bash
java -cp matsim-ai-lab-0.0.1-SNAPSHOT.jar \
  org.matsim.contrib.otfvis.OTFVis \
  --convert output/output_events.xml.gz output/output_network.xml.gz output/viz.mvi 300

# Play it back:
java -cp matsim-ai-lab-0.0.1-SNAPSHOT.jar \
  org.matsim.contrib.otfvis.OTFVis output/viz.mvi
```

Note: OTFVis is no longer actively maintained and may have issues with some GPU drivers.

### 6.3 Simunto Via (professional desktop app)

Download from https://www.simunto.com/via/ (free version available, agent limit applies).
1. Load `output/output_network.xml.gz` (File > Add Data)
2. Add Network layer
3. Load `output/output_events.xml.gz`
4. Add Agents > Vehicles layer
5. Use timeline slider to scrub through time
6. Has built-in video recording

---

## 7. LLM Plugin Integration

### Architecture

```
MATSim Config
  └── LLMConfigGroup (module name="llm")
        ├── Backend: LM_STUDIO / OPENAI / OLLAMA
        ├── LLM connection: host, port, path, model
        ├── RAG: ChromaDB host/port, source file, collection
        └── Tools: tool specification file

MATSim Controler
  └── LLMIntegrationModule (Guice)
        ├── binds IToolManager    → DefaultToolManager
        ├── binds ChatManagerContainer
        ├── binds IChatCompletionClient → ChatCompletionClientImpl
        └── binds IVectorDB       → VectorDBImplement (or null if RAG disabled)
```

### Current state

- **Working:** LLM connection via Ollama (using LM_STUDIO backend type on port 11434)
- **Working:** Chat management, tool framework, multi-turn conversations
- **Working:** Guice module integration with MATSim Controler
- **Not yet implemented:** LLM-based replanning strategies (the LLM does not influence agent behavior during simulation)
- **Not yet set up:** ChromaDB for RAG (requires Docker)

### How the LLM plugin connects to Ollama

The plugin's native Ollama backend is unimplemented. Instead, we use `BackendType.LM_STUDIO` pointed at Ollama's OpenAI-compatible endpoint (`localhost:11434/v1/chat/completions`). This works because both LM Studio and Ollama expose the same OpenAI-format API.

---

## 8. Available Scenarios

### Bundled in matsim-examples (change scenario name in `RunMatsimFromExamplesUtils.java`)

| Scenario | Complexity | Agents | Modes | Description |
|----------|-----------|--------|-------|-------------|
| `equil` | Trivial | 100 | car | Bottleneck with 9 routes. Current default. |
| `equil-extended` | Low | up to 2000 | car | Extended equil with lanes, signals, pricing |
| `kelheim` | Medium | ~1% sample | car, PT, DRT | Real German town, multimodal |
| `siouxfalls-2014` | Medium | dynamic | car, PT | Classic transport planning benchmark |
| `berlin` | High | 1% sample | car | Real Berlin network, work/education trips |
| `mielec` | High | varied | car, taxi, DRT, EV | Polish city, mobility-on-demand |
| `pt-tutorial` | Low | small | car, PT | Public transit tutorial |
| `bicycle_example` | Low | small | car, bicycle | Bicycle mode testing |

### Recommended progression
1. **equil** — verify setup works (what we did)
2. **kelheim** — first realistic scenario with multiple modes and PT
3. **siouxfalls-2014** — classic benchmark, good for policy analysis
4. **berlin** — large urban scenario

---

## 9. Key Files Reference

### Source files
| File | Purpose |
|------|---------|
| `src/main/java/org/matsim/project/RunMatsim.java` | Standard simulation entry point |
| `src/main/java/org/matsim/project/RunMatsimWithLLM.java` | Simulation + LLM post-query |
| `src/main/java/org/matsim/project/TestLLMConnection.java` | Standalone LLM connectivity test |
| `src/main/java/org/matsim/project/RunMatsimFromExamplesUtils.java` | Run scenarios from matsim-examples JAR |
| `src/main/java/matsimBinding/LLMConfigGroup.java` | All LLM configuration parameters |
| `src/main/java/matsimBinding/LLMIntegrationModule.java` | Guice module wiring LLM into MATSim |

### Config and data files
| File | Purpose |
|------|---------|
| `pom.xml` | Maven build config, dependencies, MATSim version |
| `scenarios/equil/config.xml` | Equil scenario MATSim configuration |
| `scenarios/equil/network.xml` | Road network definition |
| `scenarios/equil/plans100.xml` | Agent population and initial plans |
