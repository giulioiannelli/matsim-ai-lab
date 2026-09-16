#!/usr/bin/env bash
# Offline probe for the Ollama NATIVE API (the path used by ollama_native profiles).
#
# Four checks against OLLAMA_URL: model listed, plain chat with thinking,
# structured tool call, and embedding vector from EMBED_MODEL.
#
# Usage:
#   scripts/probes/native_probe.sh [MODEL] [EMBED_MODEL]
#   OLLAMA_URL=http://localhost:11434 scripts/probes/native_probe.sh qwen3.6:27b qwen3-embedding:0.6b
#
# Exit 0 only if all four checks pass.
set -uo pipefail

MODEL="${1:-qwen3.6:27b}"
EMBED_MODEL="${2:-qwen3-embedding:0.6b}"
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"
fail=0

check() { if [ "$2" = "1" ]; then echo "PASS  $1"; else echo "FAIL  $1"; fail=1; fi; }

tags=$(curl -fsS --max-time 10 "$OLLAMA_URL/api/tags") || { echo "FAIL  server unreachable at $OLLAMA_URL"; exit 2; }
check "model listed: $MODEL"        "$(printf '%s' "$tags" | grep -q "\"$MODEL\"" && echo 1 || echo 0)"
check "model listed: $EMBED_MODEL"  "$(printf '%s' "$tags" | grep -q "\"$EMBED_MODEL\"" && echo 1 || echo 0)"

t0=$(date +%s)
chat=$(curl -fsS --max-time 600 "$OLLAMA_URL/api/chat" -d "{
  \"model\":\"$MODEL\",\"stream\":false,\"think\":true,
  \"options\":{\"num_ctx\":4096,\"num_predict\":2048,\"temperature\":0.3},
  \"messages\":[{\"role\":\"user\",\"content\":\"In one sentence: why might a retired person prefer walking over driving for a 1 km trip?\"}]}")
t1=$(date +%s)
check "plain chat returns content ($((t1-t0))s)" "$(printf '%s' "$chat" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(1 if d["message"]["content"].strip() else 0)' 2>/dev/null || echo 0)"
check "plain chat returns thinking"             "$(printf '%s' "$chat" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(1 if d["message"].get("thinking","").strip() else 0)' 2>/dev/null || echo 0)"

t0=$(date +%s)
tool=$(curl -fsS --max-time 600 "$OLLAMA_URL/api/chat" -d "{
  \"model\":\"$MODEL\",\"stream\":false,\"think\":true,
  \"options\":{\"num_ctx\":4096,\"num_predict\":1024,\"temperature\":0.3},
  \"messages\":[{\"role\":\"user\",\"content\":\"Check which travel modes are available at facility f_12. Use the tool.\"}],
  \"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"available_modes\",\"description\":\"List transport modes available at a facility\",
    \"parameters\":{\"type\":\"object\",\"properties\":{\"fromFacilityId\":{\"type\":\"string\"}},\"required\":[\"fromFacilityId\"]}}}]}")
t1=$(date +%s)
check "tool call available_modes(f_12) ($((t1-t0))s)" "$(printf '%s' "$tool" | python3 -c 'import sys,json; d=json.load(sys.stdin); tc=d["message"].get("tool_calls",[]); print(1 if tc and tc[0]["function"]["name"]=="available_modes" and tc[0]["function"]["arguments"].get("fromFacilityId")=="f_12" else 0)' 2>/dev/null || echo 0)"

emb=$(curl -fsS --max-time 120 "$OLLAMA_URL/api/embed" -d "{\"model\":\"$EMBED_MODEL\",\"input\":\"agent walked to work and was satisfied\"}")
check "embedding vector from $EMBED_MODEL" "$(printf '%s' "$emb" | python3 -c 'import sys,json; d=json.load(sys.stdin); v=d["embeddings"][0]; print(1 if len(v)>=256 else 0)' 2>/dev/null || echo 0)"
printf '%s' "$emb" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("      dim =", len(d["embeddings"][0]))' 2>/dev/null

exit $fail
