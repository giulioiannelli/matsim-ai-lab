---
name: matsim-sync
description: "Compare vendored LLM plugin code against upstream matsim_llm_plugins repo. Use when: 'sync upstream', 'diff against plugin', 'check upstream changes', 'merge plugin changes'."
allowed-tools: ["Bash(diff *)", "Bash(git *)", "Read", "Grep", "Glob"]
---

# MATSim LLM Plugin Sync

Compare vendored code in `src/main/java/` against the upstream repo at `matsim_llm_plugins/matsim_llm_plugins/src/main/java/`.

## Vendored packages to compare

| Local path | Upstream path |
|-----------|--------------|
| `src/main/java/chatcommons/` | `matsim_llm_plugins/matsim_llm_plugins/src/main/java/chatcommons/` |
| `src/main/java/chatrequest/` | `matsim_llm_plugins/matsim_llm_plugins/src/main/java/chatrequest/` |
| `src/main/java/chatresponse/` | `matsim_llm_plugins/matsim_llm_plugins/src/main/java/chatresponse/` |
| `src/main/java/gsonprocessor/` | `matsim_llm_plugins/matsim_llm_plugins/src/main/java/gsonprocessor/` |
| `src/main/java/matsimBinding/` | `matsim_llm_plugins/matsim_llm_plugins/src/main/java/matsimBinding/` |
| `src/main/java/tools/` | `matsim_llm_plugins/matsim_llm_plugins/src/main/java/tools/` |
| `src/main/java/rag/` | `matsim_llm_plugins/matsim_llm_plugins/src/main/java/rag/` |
| `src/main/java/prompts/` | `matsim_llm_plugins/matsim_llm_plugins/src/main/java/prompts/` |
| `src/main/java/matsimdtobjects/` | `matsim_llm_plugins/matsim_llm_plugins/src/main/java/matsimdtobjects/` |

## Steps

1. For each package, run `diff -rq <local> <upstream>` to find files that differ
2. For files that differ, run `diff -u <local> <upstream>` and explain what changed
3. Check upstream git log for recent commits: `git -C matsim_llm_plugins log --oneline -10`

## Known intentional local changes (DO NOT overwrite)

These are fixes applied to vendored code for MATSim 2025.0 compatibility:
1. Removed dead `import apikeys.APIKeys` from `ChatCompletionClientImpl`
2. Null guard in `VectorDBImplement` when no RAG source file configured
3. Guarded `VectorDbProvider` to return null when RAG disabled
4. Increased OkHttp read timeout (from 10s to 10min)
5. Pinned `kotlin-stdlib:1.9.10` in pom.xml
6. protobuf-javalite exclusion in bicycle contrib
7. log4j → log4j2 migration in LLMReplanningStrategyModule

Flag these as "intentional local fix — do not revert" when showing diffs.

## Output format

Summarize as a table:
```
| File | Status | Direction | Notes |
|------|--------|-----------|-------|
| ChatCompletionClientImpl.java | differs | local fix | removed dead import |
| NewTool.java | upstream only | pull candidate | new tool added upstream |
```
