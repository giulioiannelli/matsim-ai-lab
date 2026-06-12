# 03 — LLM Plugin Architecture

This is the heart of the project: how an LLM connects to MATSim and modifies agent travel plans through structured tool calling.

## Package map

The LLM plugin is organized into 8 Java packages, each with a clear responsibility:

```
src/main/java/
├── matsimBinding/          ← MATSim integration glue (the "wiring")
│   ├── LLMIntegrationModule.java       Guice module: registers everything
│   ├── LLMConfigGroup.java             All LLM config parameters
│   ├── LLMReplanningStrategyModule.java The replanning strategy (calls LLM)
│   ├── LLMReplanningStrategyProvider.java Provider for the strategy
│   ├── AgentExperienceEventHandlerV2.java Captures trip events → Qdrant
│   ├── LLMControllerListener.java      Alternative: LLM before mobsim
│   └── LLMWithinDayListener.java       Alternative: LLM during mobsim
│
├── chatcommons/            ← Chat conversation management
│   ├── DefaultChatManager.java         Multi-turn tool-calling loop
│   ├── ChatCompletionClientImpl.java   OkHttp → LLM API (Ollama/LM Studio)
│   ├── ChatLogger.java                 Logs conversations to JSONL
│   └── ChatManagerContainer.java       Stores per-agent chat sessions
│
├── chatrequest/            ← Request serialization
│   ├── LmStudioChatRequest.java        Builds JSON for LM Studio/Ollama
│   └── OpenAiChatRequest.java          Builds JSON for OpenAI API
│
├── chatresponse/           ← Response parsing
│   ├── LmStudioChatResponse.java       Parses LM Studio/Ollama responses
│   ├── OpenAiChatResponse.java         Parses OpenAI responses
│   ├── ChatResult.java                 Wraps tool responses + stats
│   └── ChatStats.java                  Token counts, timing, success/failure
│
├── matsimdtobjects/        ← Plan ↔ JSON conversion (DTOs)
│   ├── PlanDTO.java                    Full plan: ordered activities + legs
│   ├── ActivityDTO.java                Activity: type, facility, endTime
│   ├── LegDTO.java                     Leg: mode, route, times
│   └── *RouteDTO.java                  Routes: network, transit, generic
│
├── tools/                  ← Tool-calling framework
│   ├── ITool.java                      Interface every tool implements
│   ├── DefaultToolManager.java         Registry + executor for tools
│   ├── ExternalValidator.java          Post-tool output validation
│   └── Implement/
│       ├── ExtractPlanTool.java        Dummy: extracts final plan (ends conversation)
│       ├── RouterTool.java             Real: calls MATSim TripRouter
│       └── PullAdditionalContextTool.java Real: queries Qdrant RAG
│
├── prompts/                ← System & user prompts for the LLM
│   └── IndividualPrompt.java           System prompt, plan extraction prompt
│
└── rag/                    ← Vector database abstraction
    ├── IVectorDB.java                  Interface for vector storage
    └── VectorDBImplement.java          Qdrant + LangChain4j implementation
```

## High-level flow

```
MATSim selects agent for replanning
  → LLMReplanningStrategyModule.handlePlan(plan)
  → finishReplanning():
      ├── PlanDTO.toDTOFromBaseObject(plan) → JSON string
      ├── DefaultChatManager.submit(prompt + JSON)
      │     ├── Send to Ollama via ChatCompletionClientImpl
      │     ├── Parse response → tool calls?
      │     │     ├── router_tool → TripRouter.calcRoute() → route JSON back to LLM
      │     │     ├── pull_additional_context → Qdrant.search() → docs back to LLM
      │     │     └── extract_plan → final plan (ends loop)
      │     ├── Send tool results back to LLM → next round
      │     └── Repeat until extract_plan called or max iterations
      ├── ExternalValidator checks plan consistency
      └── PopulationUtils.copyFromTo(newPlan, oldPlan)
```

## In this guide

| File | What you'll learn |
|------|-------------------|
| [chat-pipeline.md](chat-pipeline.md) | How conversations flow: manager → client → LLM → response → tools → repeat |
| [tool-framework.md](tool-framework.md) | ITool interface, dummy vs real, how to add new ones |
| [tool-registry.md](tool-registry.md) | **Provenance tracking**: which tools are upstream vs in-house, debugging guide |
| [plan-dtos.md](plan-dtos.md) | PlanDTO/ActivityDTO/LegDTO: how MATSim plans become JSON |
| [rag-and-memory.md](rag-and-memory.md) | Qdrant, embeddings, how agent experiences are stored and retrieved |
| [prompts.md](prompts.md) | System prompt design, prompt engineering decisions |
| [execution-modes.md](execution-modes.md) | replanning vs controllerlistener vs withinday |
