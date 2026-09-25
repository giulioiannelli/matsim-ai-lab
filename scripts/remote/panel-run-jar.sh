#!/usr/bin/env bash
# Server-side panel run from the fat jar (no Maven, no repo checkout). Runs in the
# deployment directory created by scripts/remote/mari.sh; same env knobs as
# scripts/panel-run.sh. Evicts our chat models on ANY exit.
set -uo pipefail
cd "$(dirname "$0")/.."
OLLAMA_URL="${OLLAMA_URL:-http://127.0.0.1:11434}"
MODEL="${MODEL:-qwen3.6:27b}"
PLANS="${PLANS:-ground/siouxfalls-s0.10-b10-seed4711.plans.xml.gz}"
CAPF="${CAPF:-0.1}"; ITERS="${ITERS:-20}"; PANEL="${PANEL:-200}"; BUDGET="${BUDGET:-10}"
QUANTILE="${QUANTILE:-0.2}"; CAP="${CAP:-10}"; SEED="${SEED:-4721}"; EXTRA="${EXTRA:-}"
HEAP="${HEAP:-8g}"
evict() {
    # unload only instances we loaded: our model name at a context size other than 4096
    # (4096 is the size another user runs the same model at; we share that one, never evict it)
    curl -s -m 10 "$OLLAMA_URL/api/ps" | python3 -c '
import json, sys, urllib.request
url = sys.argv[1]
for m in json.load(sys.stdin)["models"]:
    if m["name"] in sys.argv[2].split(",") and m.get("context_length") != 4096:
        req = urllib.request.Request(url + "/api/generate", data=json.dumps({"model": m["name"], "keep_alive": 0}).encode())
        urllib.request.urlopen(req, timeout=60); print("evicted", m["name"], m.get("context_length"))
' "$OLLAMA_URL" "qwen3.6:27b,qwen3.5:9b" || true
}
trap evict EXIT
bin/services.sh status >/dev/null
echo "=== PANEL RUN (server, jar): $MODEL ${ITERS}it panel${PANEL} budget${BUDGET} q${QUANTILE} seed${SEED} ground=$PLANS cap=$CAPF extra=[$EXTRA] ==="
java -Xmx"$HEAP" -cp bin/matsim-ai-lab-0.0.1-SNAPSHOT.jar org.matsim.project.RunSiouxFallsLLMAgents \
    "$ITERS" "$MODEL" --panel --num-agents="$PANEL" --max-queries="$BUDGET" --trigger-quantile="$QUANTILE" \
    --plans-file="$PLANS" --capacity-factor="$CAPF" --prompt-variant=persona --enable-comparison-tools \
    --max-tool-iterations="$CAP" --seed="$SEED" --embedding-host=127.0.0.1 --embedding-port=11435 $EXTRA
status=$?
echo "=== PANEL RUN ENDED (exit $status) ==="
exit $status
