#!/usr/bin/env bash
# Offline reasoning probe.
#
# Hits the local Ollama endpoint with 5 varied prompts and asserts that the
# response has a populated `message.reasoning` field (or <think> tags in
# content) — without this, the Java reasoning-capture path
# (OpenAiCompatChatResponse, DefaultChatManager) has nothing to read.
#
# Usage:
#   scripts/probes/reasoning_probe.sh [MODEL]
#
# MODEL defaults to qwen3.5. Override to probe a new candidate:
#   scripts/probes/reasoning_probe.sh deepseek-r1-distill-qwen:7b
#
# Exit codes:
#   0  all 5 probes emit reasoning
#   1  at least one probe emits no reasoning (model not thinking via our path)
#   2  network / server error

set -euo pipefail

MODEL="${1:-qwen3.5}"
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"
ENDPOINT="${OLLAMA_URL}/v1/chat/completions"

PROMPTS=(
  "What is 2+2? Think briefly."
  "List three Sioux Falls transportation modes and decide which would be faster for a 2km commute."
  "A person has a car license and is 67 years old. Would they prefer car or transit for a downtown trip? Explain your reasoning."
  "An agent's original plan has car for the morning trip (395s) but the router returned 37259s for the return trip. What is likely wrong?"
  "Given activities: home->work at 07:05, work->home at 17:25. Does this plan pass basic sanity checks?"
)

green() { printf "\033[32m%s\033[0m\n" "$*"; }
red()   { printf "\033[31m%s\033[0m\n" "$*"; }
bold()  { printf "\033[1m%s\033[0m\n" "$*"; }

bold "=== Reasoning probe — model: ${MODEL} ==="
echo "endpoint: ${ENDPOINT}"
echo

ok=0
fail=0
for i in "${!PROMPTS[@]}"; do
  idx=$((i+1))
  prompt="${PROMPTS[$i]}"
  echo "[$idx/5] $prompt"

  body=$(jq -n --arg m "$MODEL" --arg p "$prompt" \
          '{model:$m, messages:[{role:"user", content:$p}], temperature:0.3, max_tokens:1024, stream:false, enable_thinking:true}')

  resp=$(curl -sS -m 120 -X POST "$ENDPOINT" \
           -H "Content-Type: application/json" -H "Authorization: ollama" \
           -d "$body") || { red "  -> network/server error"; exit 2; }

  reasoning_len=$(echo "$resp" | jq -r '(.choices[0].message.reasoning // "") | length' 2>/dev/null || echo 0)
  content=$(echo "$resp" | jq -r '.choices[0].message.content // ""')
  think_len=$(printf '%s' "$content" | grep -oP '(?s)<think>.*?</think>' | wc -c || echo 0)

  if [[ "$reasoning_len" -gt 0 ]] || [[ "$think_len" -gt 0 ]]; then
    green "  -> OK  reasoning=${reasoning_len} chars  think_tag=${think_len} chars"
    ok=$((ok+1))
  else
    red "  -> FAIL  no reasoning emitted (reasoning field empty and no <think> in content)"
    fail=$((fail+1))
  fi
done

echo
bold "=== Summary ==="
echo "model:     ${MODEL}"
echo "passed:    ${ok}/5"
echo "failed:    ${fail}/5"

if [[ "$fail" -gt 0 ]]; then
  red "GATE FAILED — this model does not surface reasoning via our Ollama /v1 path."
  red "Investigation: model may need a native /api/chat call, a specific system prompt, or isReasoning=false in the profile."
  exit 1
fi

green "GATE PASSED — model emits reasoning; our Java stack will capture it."
