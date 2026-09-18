#!/usr/bin/env bash
# Does the server honour keep_alive? Sends two tiny chat requests with the
# same runner options as the simulation (so no reload is needed for config
# reasons) a few seconds apart and prints Ollama's load_duration for each.
# Second load ≈ 0 ms → model stayed resident. Second load ≈ 15–20 s → it was
# unloaded in between (server ignores keep_alive, or another tenant's model
# evicted ours).
#   scripts/probes/keepalive_probe.sh [MODEL] [GAP_SECONDS]
set -uo pipefail
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"
MODEL="${1:-qwen3.6:27b}"
GAP="${2:-5}"
req() {
  curl -s --max-time 300 "$OLLAMA_URL/api/chat" -d "{\"model\":\"$MODEL\",\"stream\":false,\"think\":false,\"keep_alive\":\"10m\",\"options\":{\"num_ctx\":12288,\"num_gpu\":999,\"num_predict\":4},\"messages\":[{\"role\":\"user\",\"content\":\"Say OK.\"}]}" \
    | python3 -c 'import sys,json; o=json.load(sys.stdin); print("load %.1f s  total %.1f s" % (o.get("load_duration",0)/1e9, o.get("total_duration",0)/1e9))'
}
echo "request 1: $(req)"; sleep "$GAP"; echo "request 2 after ${GAP}s: $(req)"
curl -s "$OLLAMA_URL/api/ps" | python3 -c 'import sys,json; [print("resident:", m["name"], "until", m["expires_at"][11:19]) for m in json.load(sys.stdin)["models"]]'
