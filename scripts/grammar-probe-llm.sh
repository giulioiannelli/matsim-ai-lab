#!/usr/bin/env bash
# grammar-probe-llm.sh — short LLM probes (no MATSim) verifying the grammar schema
# is accepted by Ollama AND produces parseable JSON for diverse prompts.
#
# Usage:
#   bash scripts/grammar-probe-llm.sh                 # 3 default prompts
#   OLLAMA_PORT=11434 bash scripts/grammar-probe-llm.sh
#
# Exit 0 only if every prompt returns a valid {thought, tool_call:{name in enum, arguments:{...}}}.

: "${OLLAMA_PORT:=11434}"
: "${MODEL:=qwen3.5}"
: "${MAX_TOKENS:=1500}"
ENDPOINT="http://localhost:${OLLAMA_PORT}/v1/chat/completions"

# Schema produced by GrammarSchema.build for the 6 registered tools, with
# nested PlanDTO / wrapped scalars already simplified by flattenWrappedScalars.
read -r -d '' SCHEMA <<'JSON'
{
  "type":"object",
  "properties":{
    "thought":{"type":"string"},
    "tool_call":{
      "oneOf":[
        {"type":"object","properties":{"name":{"const":"router_tool"},"arguments":{"type":"object","properties":{"fromFacilityId":{"type":"string"},"toFacilityId":{"type":"string"},"mode":{"type":"string"},"departureTimeSeconds":{"type":"number"}},"required":["fromFacilityId","toFacilityId","mode","departureTimeSeconds"]}},"required":["name","arguments"]},
        {"type":"object","properties":{"name":{"const":"extract_plan"},"arguments":{"type":"object","properties":{"plan":{"type":"object"}},"required":["plan"]}},"required":["name","arguments"]},
        {"type":"object","properties":{"name":{"const":"activity_chain_summary"},"arguments":{"type":"object"}},"required":["name","arguments"]},
        {"type":"object","properties":{"name":{"const":"available_modes"},"arguments":{"type":"object","properties":{"fromFacilityId":{"type":"string"}},"required":["fromFacilityId"]}},"required":["name","arguments"]},
        {"type":"object","properties":{"name":{"const":"validate_timing"},"arguments":{"type":"object","properties":{"plan":{"type":"object"}},"required":["plan"]}},"required":["name","arguments"]},
        {"type":"object","properties":{"name":{"const":"pull_additional_context"},"arguments":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}},"required":["name","arguments"]}
      ]
    }
  },
  "required":["thought","tool_call"]
}
JSON

VALID_NAMES='["router_tool","extract_plan","activity_chain_summary","available_modes","validate_timing","pull_additional_context"]'

probe() {
    local label="$1" user="$2"
    python3 - "$label" "$user" "$SCHEMA" "$VALID_NAMES" "$ENDPOINT" "$MODEL" "$MAX_TOKENS" <<'PY'
import json, sys, urllib.request, urllib.error
label, user, schema_str, valid_names_str, endpoint, model, max_tokens = sys.argv[1:8]
schema = json.loads(schema_str)
valid_names = set(json.loads(valid_names_str))

payload = {
    "model": model,
    "messages": [
        {"role":"system","content":"You are an AI agent. Reply only via the constrained format."},
        {"role":"user","content": user}
    ],
    "max_tokens": int(max_tokens),
    "temperature": 0.1,
    "response_format": {"type":"json_schema", "json_schema": {"name":"ToolCall","schema":schema}}
}
try:
    req = urllib.request.Request(endpoint, data=json.dumps(payload).encode(),
                                 headers={"Content-Type":"application/json"})
    body = urllib.request.urlopen(req, timeout=120).read()
except urllib.error.URLError as e:
    print(f"[FAIL] {label}: HTTP error {e}")
    sys.exit(2)

resp = json.loads(body)
if "error" in resp:
    print(f"[FAIL] {label}: backend error: {resp['error'].get('message')}")
    sys.exit(2)

content = resp.get("choices",[{}])[0].get("message",{}).get("content","")
finish = resp.get("choices",[{}])[0].get("finish_reason","?")

try:
    obj = json.loads(content)
except Exception as e:
    print(f"[FAIL] {label}: content not parseable JSON ({e}); finish={finish}; content={content[:200]!r}")
    sys.exit(2)

name = obj.get("tool_call",{}).get("name")
if name not in valid_names:
    print(f"[FAIL] {label}: tool name {name!r} not in enum")
    sys.exit(2)

args = obj.get("tool_call",{}).get("arguments")
if not isinstance(args, dict):
    print(f"[FAIL] {label}: arguments not an object")
    sys.exit(2)

print(f"[OK]   {label}: name={name} args_keys={list(args.keys())} finish={finish}")
PY
}

PASS=0; FAIL=0
for entry in \
    "simple-commute|Plan: home->work->home by car. Pick the next tool to call." \
    "pt-trip|Plan has a public transport leg from home to a museum. Pick the next tool." \
    "multimodal|Plan mixes walk, bike, and car. The agent is at facility 21554_16. What tool next?"; do
    label="${entry%%|*}"
    prompt="${entry#*|}"
    if probe "$label" "$prompt"; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
done

echo
echo "Probes: ${PASS} ok, ${FAIL} failed."
[ "$FAIL" -eq 0 ]
