# LLMConfigGroup — Config Reference

> **Source file**: `matsimBinding/LLMConfigGroup.java`

All parameters set via `llmConfig.setXxx()` in the runner class.

## Chat completion (LLM)

| Parameter | Setter | Default | Description |
|-----------|--------|---------|-------------|
| Backend | `setBackend(BackendType)` | `LM_STUDIO` | Use `LM_STUDIO` for Ollama |
| Host | `setLlmHost(String)` | `"localhost"` | LLM server host |
| Port | `setLlmPort(int)` | `1234` | LM Studio default; use `11434` for Ollama |
| Path | `setLlmPath(String)` | `"/v1/chat/completions"` | OpenAI-compatible endpoint |
| HTTPS | `setUseHttps(boolean)` | `false` | Use HTTPS? (true for cloud APIs) |
| Model | `setModelName(String)` | varies | Model name as Ollama knows it (e.g., `"qwen3.5"`) |
| Temperature | `setTemperature(double)` | `0.3` | 0=deterministic, 1=creative |
| Max tokens | `setMaxTokens(int)` | `4096` | Maximum response length |
| Authorization | `setAuthorization(String)` | `"lm-studio"` | Auth header; use `"ollama"` for Ollama |

## Embedding (for RAG)

| Parameter | Setter | Default | Description |
|-----------|--------|---------|-------------|
| Embedding path | `setEmbeddingPath(String)` | `"/v1/embeddings"` | Ollama serves this |
| Embedding model | `setEmbeddingModelName(String)` | varies | e.g., `"nomic-embed-text"` |

## Vector DB (Qdrant)

| Parameter | Setter | Default | Description |
|-----------|--------|---------|-------------|
| Host | `setVectorDbHost(String)` | `"localhost"` | Qdrant server host |
| Port | `setVectorDbPort(int)` | `6334` | Qdrant gRPC port |
| Collection | `setVectorDbCollectionName(String)` | varies | Auto-created if absent |
| Source file | `setVectorDBSourceFile(String)` | `""` | Static context file for RAG |
| Cleanup | `setCleanVectorDbUponCompletion(String)` | `"ALL"` | `ALL`, `DYNAMIC`, or `NONE` |

## LLM replanning control

| Parameter | Setter | Default | Description |
|-----------|--------|---------|-------------|
| AI agents | `setNumberOfAIAgents(int)` | `100` | Number of LLM-controlled agents (-1 = all) |
| Start iteration | `setIterationToStartAIActivity(int)` | `0` | When LLM replanning begins |
| Max tool iterations | `setMaxToolIterations(int)` | `10` | Cap on tool-calling rounds per conversation |

## Recommended settings for local GPU (RTX 3080, 16GB VRAM)

```java
llmConfig.setBackend(BackendType.LM_STUDIO);
llmConfig.setLlmHost("localhost");
llmConfig.setLlmPort(11434);
llmConfig.setLlmPath("/v1/chat/completions");
llmConfig.setModelName("qwen3.5");
llmConfig.setUseHttps(false);
llmConfig.setAuthorization("ollama");
llmConfig.setTemperature(0.3);
llmConfig.setMaxTokens(4096);
llmConfig.setEmbeddingPath("/v1/embeddings");
llmConfig.setEmbeddingModelName("nomic-embed-text");
llmConfig.setVectorDbHost("localhost");
llmConfig.setVectorDbPort(6334);
llmConfig.setNumberOfAIAgents(5);
llmConfig.setMaxToolIterations(10);
```
