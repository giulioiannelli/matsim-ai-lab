# Chat Pipeline

> **Source files**: `chatcommons/DefaultChatManager.java`, `chatcommons/ChatCompletionClientImpl.java`

## Overview

The chat pipeline manages the conversation between MATSim and the LLM. For each AI agent, there's a dedicated `DefaultChatManager` that maintains conversation history and orchestrates multi-turn tool calling.

## The conversation flow

```
LLMReplanningStrategyModule
  → chat.submit(userMessage, validators)
     │
     └── DefaultChatManager.submit()
          │
          ├── LOOP (until extract_plan called or max iterations):
          │    │
          │    ├── submitInternal(messages)
          │    │    └── ChatCompletionClientImpl.sendRequest()
          │    │         ├── Build JSON: {model, messages, tools, temperature, max_tokens}
          │    │         ├── HTTP POST to localhost:11434/v1/chat/completions
          │    │         ├── Log request+response to JSONL (ChatLogger)
          │    │         └── Parse response → IChatCompletionResponse
          │    │
          │    ├── Response has no tool calls?
          │    │    → Retry up to 3 times with "You must respond with a tool call"
          │    │
          │    ├── Response has tool calls?
          │    │    ├── For each tool call:
          │    │    │    ├── toolManager.runToolCall(call, vectorDB, context)
          │    │    │    ├── If ExternalValidator exists → validate output
          │    │    │    │    └── Validation fails → send error back to LLM
          │    │    │    └── Track stats (parsing failures, execution failures)
          │    │    │
          │    │    ├── All tools were dummy? → BREAK (success)
          │    │    └── Some tools were real? → send results back to LLM → CONTINUE
          │    │
          │    └── Iteration count > maxToolIterations? → BREAK (failure)
          │
          └── Return ChatResult { toolResponses, stats }
```

## Per-agent chat sessions

Each AI agent gets its own `DefaultChatManager` at startup:

```java
// LLMReplanningStrategyModule.notifyStartup()
IChatManager chatManager = new DefaultChatManager(
    Id.create(personId, IChatManager.class),  // unique ID per agent
    chatClient,      // shared HTTP client (thread-safe)
    toolManager,     // shared tool registry
    vectorDB,        // shared Qdrant connection
    llmConfig        // shared config
);
chatManager.setSystemMessage(systemPrompt + " You are person " + personId);
chatManager.setPersonId(personId);  // for metadata filtering in RAG
```

The `ChatManagerContainer` stores all sessions by person ID. Before each iteration, `chat.clear()` resets the conversation history (but the system message persists).

## The HTTP layer

`ChatCompletionClientImpl` sends requests using OkHttp:

```java
// ChatCompletionClientImpl.java
OkHttpClient client = new OkHttpClient.Builder()
    .readTimeout(10, TimeUnit.MINUTES)   // Long timeout for local inference
    .connectTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build();
```

The request format follows the OpenAI Chat Completions API:

```json
{
  "model": "qwen3.5",
  "temperature": 0.3,
  "max_tokens": 4096,
  "stream": false,
  "enable_thinking": false,
  "messages": [
    {"role": "system", "content": "You are an AI agent controlling..."},
    {"role": "user", "content": "The original daily plan...{JSON}"}
  ],
  "tools": [
    {"type": "function", "function": {"name": "extract_plan", ...}},
    {"type": "function", "function": {"name": "router_tool", ...}},
    {"type": "function", "function": {"name": "pull_additional_context", ...}}
  ]
}
```

This works with Ollama (port 11434), LM Studio (port 1234), and OpenAI's API — all use the same format.

## Stats tracking

Every conversation collects `ChatStats`:

| Metric | What it measures |
|--------|-----------------|
| `llmRounds` | Number of LLM API calls (1 for simple plans, 3-5 for PT plans with routing) |
| `totalToolCalls` | How many tools the LLM invoked |
| `toolParsingFailures` | LLM sent malformed tool arguments |
| `toolVerificationFailures` | Tool arguments failed validation |
| `toolExecutionFailures` | Tool execution threw an error |
| `noToolCallRetries` | Times the LLM responded with text instead of tool calls |
| `durationMs` | Total wall clock time |
| `promptTokens` / `completionTokens` | Token usage |

## Conversation logging

`ChatLogger` writes every request/response pair to `llm_chat_log_ChatLog_combined.jsonl`. This includes the full request body (system prompt + plan JSON + tool schemas) and the full response body (reasoning traces + tool calls + token usage). This is the primary data source for analysis.

## Where to refine

- **Retry logic** (`DefaultChatManager.java:267-291`): Currently retries 3 times when LLM doesn't produce tool calls. Could be smarter (e.g., rephrase the prompt).
- **Parallel agent processing**: Currently sequential. Could process multiple agents concurrently since each has its own ChatManager.
- **Conversation history**: Currently cleared each iteration. Could carry forward for cross-iteration memory.
