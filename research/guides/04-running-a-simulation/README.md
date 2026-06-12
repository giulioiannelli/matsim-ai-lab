# 04 — Running a Simulation

## Prerequisites

| Service | How to start | Verify |
|---------|-------------|--------|
| **Java 21+** | Already installed | `java -version` |
| **Ollama** | Runs as daemon | `ollama list` |
| **qwen3.5 model** | `ollama pull qwen3.5` | Should show in `ollama list` |
| **nomic-embed-text** | `ollama pull nomic-embed-text` | Should show in `ollama list` |
| **Docker** | `sudo systemctl start docker` | `docker ps` |
| **Qdrant** | `sudo docker run -d -p 6333:6333 -p 6334:6334 qdrant/qdrant` | `curl localhost:6333/healthz` |

## Quick start

```bash
# Compile
./mvnw compile

# Run 10 iterations with qwen3.5 (recommended)
./mvnw -q compile exec:java \
  -Dexec.mainClass="org.matsim.project.RunSiouxFallsLLMAgents" \
  -Dexec.args="10 qwen3.5"
```

Arguments: `[iterations] [modelName]`

## What happens during a run

1. Loads Sioux Falls 2014 scenario (84,110 agents, transit network)
2. Selects 5 agents as AI agents (LLM-controlled)
3. Connects to Qdrant, creates collection for agent memory
4. Runs iterations 0 through N:
   - Iteration 0: baseline simulation (no LLM replanning)
   - Iteration 1+: LLM replanning fires each iteration
   - Each AI agent's plan is sent to the LLM
   - LLM reasons, calls tools, returns modified plan
   - MATSim scores all plans, keeps the best

## Timing expectations (RTX 3080 Laptop, 16GB VRAM)

| Phase | Time per iteration |
|-------|-------------------|
| MATSim mobsim | ~15 seconds |
| LLM replanning (4 agents) | 30s — 5 min (depends on plan complexity) |
| Scoring + analysis | ~2 seconds |
| **Total per iteration** | **~1-6 minutes** |

A 10-iteration run takes roughly 20-40 minutes.

## Configuration

Edit `RunSiouxFallsLLMAgents.java` to change parameters. See [config-reference.md](config-reference.md) for all parameters.

Key ones to tweak:
- `llmConfig.setNumberOfAIAgents(5)` — more agents = longer replanning
- `llmConfig.setMaxTokens(4096)` — lower = faster but less reasoning
- `llmConfig.setMaxToolIterations(10)` — cap on tool-calling rounds

## Output

Everything goes to `output/siouxfalls-llm-agents/` (gitignored). See [output-files.md](output-files.md) for what each file contains.

The most important output for analysis:
```bash
# Full LLM conversation logs (JSON lines)
output/siouxfalls-llm-agents/llm_chat_log_ChatLog_combined.jsonl

# Per-agent replanning stats
output/siouxfalls-llm-agents/llm_person_stats_combined.csv
```

## Advanced: Full CLI runner (Run.java)

> **Source files**: `src/main/java/run/Run.java`, `src/main/java/run/RunFromEclipse.java`

The upstream plugin includes a picocli-based runner with full command-line control over every parameter. Unlike `RunSiouxFallsLLMAgents` (which hardcodes most settings), `Run.java` accepts everything via CLI args. `RunFromEclipse.java` shows example argument sets.

```bash
./mvnw -q compile exec:java -Dexec.mainClass="run.Run" -Dexec.args="
  --config data/config.xml
  --network data/network.xml
  --plan data/population.xml.gz
  --facilities data/facilities.xml.gz
  --ts data/transitSchedule.xml
  --tv data/transitVehicles.xml
  --iterations 50
  --thread 8
  --scale 0.01
  --output output/my-run
  --llmHost localhost
  --llmPort 11434
  --llmPath /v1/chat/completions
  --llmModelName qwen3.5
  --embeddingPath /v1/embeddings
  --embeddingModelName nomic-embed-text
  --authorization ollama
  --backend lmstudio
  --vectorDbHost localhost
  --vectorDbPort 6334
  --vectorDbCollectionName my_collection
  --cleanVectorDbUponCompletion ALL
  --LLMConnectionOption replanning
  --numberOfAIAgent 10
  --iterationToStartAIAgent 0
  --maxOutputToken 4096
  --maxToolIteration 10
"
```

### All CLI arguments

#### Scenario inputs
| Arg | Default | Description |
|-----|---------|-------------|
| `--config` | `config.xml` | MATSim config file |
| `--network` | `montreal_network.xml.gz` | Network file |
| `--plan` | `prepared_population.xml.gz` | Population/plans file |
| `--facilities` | `montreal_facilities.xml.gz` | Activity facilities |
| `--ts` | `montreal_transit_schedules.xml.gz` | Transit schedule |
| `--tv` | `montreal_transit_vehicles.xml.gz` | Transit vehicles |
| `--household` | `montreal_households.xml.gz` | Households file |
| `--vehicles` | `null` | Auto vehicle definitions |
| `--lanes` | `null` | Lane definitions |
| `--paramfile` | `paramReaderTrial1.csv` | Parameter reader config |

#### Simulation control
| Arg | Default | Description |
|-----|---------|-------------|
| `--iterations` | `250` | Max iterations |
| `--firstIteration` | `0` | Start iteration |
| `--thread` | `40` | Number of threads |
| `--scale` | `0.05` | Population scale factor |
| `--output` | `output/` | Output directory |
| `--clearplan` | `false` | Clear routes and non-selected plans |
| `--ifScaleDownPt` | `true` | Scale down PT capacity |

#### LLM configuration
| Arg | Default | Description |
|-----|---------|-------------|
| `--llmHost` | `localhost` | LLM server host |
| `--llmPort` | `1234` | LLM port (use 11434 for Ollama) |
| `--llmPath` | `/v1/chat/completions` | Chat endpoint path |
| `--llmModelName` | `qwen/qwen3.5-9b` | Model name |
| `--backend` | `lmstudio` | `openai`, `lmstudio`, or `ollama` |
| `--authorization` | `lm-studio` | Auth token (use `ollama` for Ollama) |
| `--maxOutputToken` | `10000` | Max response tokens |

#### Embedding & RAG
| Arg | Default | Description |
|-----|---------|-------------|
| `--embeddingPath` | `/v1/embeddings` | Embedding endpoint |
| `--embeddingModelName` | `text-embedding-granite-...` | Embedding model |
| `--vectorDbHost` | `localhost` | Qdrant host |
| `--vectorDbPort` | `6334` | Qdrant gRPC port |
| `--vectorDbCollectionName` | `matsim_llm_test_collection` | Collection name |
| `--vectorDbSourceFile` | `data/static.txt` | Static context file |
| `--cleanVectorDbUponCompletion` | `ALL` | Cleanup: `NONE`, `DYNAMIC`, `ALL` |

#### LLM replanning control
| Arg | Default | Description |
|-----|---------|-------------|
| `--LLMConnectionOption` | `replanning` | `replanning`, `controllerlistener`, `withinday` |
| `--numberOfAIAgent` | `100` | AI agent count (-1 = all eligible) |
| `--iterationToStartAIAgent` | `0` | When LLM replanning starts |
| `--maxToolIteration` | `10` | Max tool-calling rounds |

Note: `Run.java` is designed for custom scenarios with your own data files. For bundled matsim-examples scenarios, use `RunSiouxFallsLLMAgents` instead.

## Other scenarios (no LLM)

```bash
# Sioux Falls baseline
./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.RunSiouxFalls" -Dexec.args="100"

# Kelheim multimodal
./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.RunKelheim" -Dexec.args="50"

# Unit test (equil scenario)
./mvnw test -Dtest=RunMatsimTest
```

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Qdrant not running | `sudo docker start $(sudo docker ps -aq --filter ancestor=qdrant/qdrant)` |
| Model not found | `ollama pull modelname` |
| protobuf error | Check `protobuf-javalite` is excluded in pom.xml |
| LLM timeout | Reduce `maxTokens` or `maxToolIterations` |
| Agent crashes simulation | Error handling catches it; agent keeps original plan |
