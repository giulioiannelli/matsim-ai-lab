# RAG and Agent Memory

> **Source files**: `rag/VectorDBImplement.java`, `matsimBinding/AgentExperienceEventHandlerV2.java`, `tools/Implement/PullAdditionalContextTool.java`

## The memory system

Each AI agent accumulates experiences during simulation. These experiences are stored in Qdrant (a vector database) and can be retrieved during replanning to inform the LLM's decisions.

## Architecture

```
During mobsim:
  Events fire → AgentExperienceEventHandlerV2 listens
    → Builds text narratives ("Person took car, 6 min, congestion on link 56_5")
    → Embeds text via nomic-embed-text (Ollama /v1/embeddings)
    → Stores in Qdrant with metadata {personId, type, mode, timeBand}

During replanning:
  LLM calls pull_additional_context("morning commute experience")
    → PullAdditionalContextTool embeds query
    → Searches Qdrant (filtered by personId)
    → Returns top-K matching documents
    → LLM reads past experiences and adjusts plan accordingly
```

## What gets stored

`AgentExperienceEventHandlerV2` tracks:
- **Trip departures**: mode, time, origin link
- **Link-level times**: how long each road segment took (congestion detection)
- **PT waiting times**: how long the agent waited for a bus/tram
- **Trip arrivals**: total travel time, destination

After each trip, it builds a natural language summary and inserts it into Qdrant.

## Per-agent isolation

Metadata filtering ensures each agent only sees its own experiences:

```java
// DefaultChatManager.buildMetadataFilter()
Map<String, String> filter = new HashMap<>();
filter.put("personId", this.personId.toString());
```

Person 9285_1 cannot see what happened to person 25875_1.

## Qdrant setup

- **Host**: localhost:6334 (gRPC port)
- **Collection**: auto-created per simulation run (e.g., `matsim_siouxfalls_llm`)
- **Embedding model**: `nomic-embed-text` via Ollama (768-dim vectors)
- **Cleanup**: configurable — `ALL` (delete after run), `DYNAMIC` (keep static), `NONE` (persist)

## Static vs dynamic content

- **Static**: loaded from a file at startup (e.g., scenario description, mode information)
- **Dynamic**: accumulated during simulation (trip experiences, congestion reports)

The `VectorDBSourceFile` config parameter points to a static context file. Dynamic content is added by the event handler during each iteration.

## Where to refine

- **Richer experiences**: Include score feedback ("this plan scored -15.2, worse than yesterday")
- **Cross-agent learning**: Store aggregate patterns ("agents in zone 5 prefer PT in morning")
- **Temporal decay**: Weight recent experiences higher than old ones
- **Persona memory**: Accumulate behavioral preferences that persist across scenarios
