# chatrequest.grammar — JSON-schema constrained tool dispatch

Opt-in replacement for the free `tools:` field with an enforced `response_format:
{type: "json_schema", ...}`. When enabled, the LLM's sampler is grammar-constrained
at the token level and literally cannot emit hallucinated tool names or malformed
arguments.

## Files

- `GrammarSchema.java` — builds a `oneOf` schema over registered tool functions.
  Each branch pins the tool name with `const` and uses that tool's existing
  parameter schema.
- `GrammarMode.java` — feature flag + two adapter methods:
  `rewriteRequestBody(body, schemas)` strips `tools` and injects `response_format`;
  `adaptResponse(response)` wraps the response to expose the LLM's content as a
  synthetic `IToolCall`. See `chatresponse.grammar.GrammarResponseParser`.

## Flag

```
-Dmatsim.llm.grammar=true
```

Off by default → baseline behavior unchanged. Composes with `StagedToolFilter`:
the filter picks visible tool names, the grammar encodes them as the schema's
allowed branches.

## Backends

Tested against Ollama ≥ 0.5 `/v1/chat/completions` which honors the
`response_format: {type: "json_schema", json_schema: {..., strict: true}}`
envelope (verified with qwen3.5).
