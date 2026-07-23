#!/usr/bin/env bash
# GPU hygiene for the Ollama server (local or remote via SSH tunnel).
#
#   scripts/ollama-gpu.sh status   # list models resident in GPU memory
#   scripts/ollama-gpu.sh evict    # unload every resident model immediately
#
# Endpoint defaults to the tunnel/local port; override with OLLAMA_URL.
set -euo pipefail

OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"

loaded_models() {
    curl -sf --max-time 10 "$OLLAMA_URL/api/ps" \
        | python3 -c "import json,sys; [print(m['name'], round(m['size_vram']/1e9,1), 'GB') for m in json.load(sys.stdin)['models']]"
}

case "${1:-status}" in
    status)
        out=$(loaded_models)
        if [[ -z "$out" ]]; then echo "GPU memory clean — no models loaded."; else echo "$out"; fi
        ;;
    evict)
        names=$(loaded_models | awk '{print $1}')
        if [[ -z "$names" ]]; then echo "Nothing to evict."; exit 0; fi
        for name in $names; do
            echo "Evicting $name ..."
            curl -sf --max-time 30 "$OLLAMA_URL/api/generate" \
                -d "{\"model\":\"$name\",\"keep_alive\":0}" > /dev/null
        done
        echo "Done. Remaining:"
        loaded_models || true
        ;;
    *)
        echo "Usage: $0 [status|evict]" >&2
        exit 1
        ;;
esac
