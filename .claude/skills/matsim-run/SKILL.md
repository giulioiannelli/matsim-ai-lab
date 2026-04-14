---
name: matsim-run
description: "Launch MATSim scenarios with pre-flight checks, monitor output, compare runs. Use when: 'run scenario', 'run simulation', 'launch MATSim', 'check Ollama', 'compare runs', 'run siouxfalls', 'run kelheim'."
argument-hint: "[scenario-or-action: check|siouxfalls|equil|kelheim|compare]"
allowed-tools: ["Bash(./mvnw *)", "Bash(ollama *)", "Bash(nvidia-smi *)", "Bash(curl *)", "Bash(tail *)", "Bash(ls *)", "Bash(wc *)", "Bash(zcat *)", "Read", "Grep"]
---

# MATSim Scenario Runner

## Pre-flight checks (always run first for LLM scenarios)

1. `ollama list` — verify model is loaded (need qwen3.5 or similar)
2. `curl -sf http://localhost:11434/v1/models` — verify Ollama endpoint
3. `nvidia-smi` — check GPU VRAM usage (RTX 3080 16GB)
4. If Qdrant needed: `curl -sf http://localhost:6333/collections` — verify vector DB

If any check fails, report clearly and suggest fix (e.g., `ollama serve`, `ollama pull qwen3.5`, `sudo docker start qdrant`).

## Scenario mapping

| Argument | Main Class | Default Args | Output Dir |
|----------|-----------|-------------|------------|
| `siouxfalls` | `RunSiouxFallsLLMAgents` | `20 qwen3.5` | `output/siouxfalls-llm-agents/` |
| `siouxfalls-baseline` | `RunSiouxFalls` | `100` | `output/siouxfalls/` |
| `equil` | `RunMatsim` | (none) | `output/equil/` |
| `kelheim` | `RunKelheim` | `50` | `output/kelheim/` |

## Launch command pattern

```bash
./mvnw -q compile exec:java -Dexec.mainClass="org.matsim.project.<MainClass>" -Dexec.args="<args>"
```

## After run completes

1. List output directory contents
2. Show final line of `modestats.csv` (mode shares)
3. Count lines in `llm_chat_log_ChatLog_combined.jsonl` (if exists)
4. Report any warnings from `logfileWarningsErrors.log`
5. Suggest running `/matsim-analyze` for detailed analysis

## Compare action

When argument is `compare <dir1> <dir2>`:
1. Diff `modestats.csv` final lines between dirs
2. Diff `scorestats.csv` final lines
3. Compare LLM person stats if both have them
4. Summarize key differences

## Check action

When argument is `check`: only run pre-flight checks, don't launch anything.
