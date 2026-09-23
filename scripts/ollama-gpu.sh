#!/usr/bin/env bash
# GPU hygiene for the Ollama server (local or remote via SSH tunnel).
#
#   scripts/ollama-gpu.sh status      # list models resident in GPU memory
#   scripts/ollama-gpu.sh evict       # unload OUR resident models (OLLAMA_OUR_MODELS)
#   scripts/ollama-gpu.sh evict-all   # unload every resident model (shared server: use with care)
#
# Endpoint defaults to the tunnel/local port; override with OLLAMA_URL.
# The server may be shared with other users: plain `evict` only unloads models
# in OLLAMA_OUR_MODELS (space-separated names), never someone else's job. A
# colleague may run the SAME model name at another context size (Ollama keeps
# one instance per name), so an instance whose context_length differs from
# OLLAMA_OUR_CTX (our num_ctx; empty = ignore) is theirs and is left alone.
set -euo pipefail

OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"
OLLAMA_OUR_MODELS="${OLLAMA_OUR_MODELS:-qwen3.6:27b qwen3.5:9b qwen3.5:0.8b qwen3-embedding:0.6b nomic-embed-text:latest}"
OLLAMA_OUR_CTX="${OLLAMA_OUR_CTX-12288}"

loaded_models() {
    curl -sf --max-time 10 "$OLLAMA_URL/api/ps" \
        | python3 -c "import json,sys; [print(m['name'], round(m['size_vram']/1e9,1), 'GB', 'ctx', m.get('context_length', '?')) for m in json.load(sys.stdin)['models']]"
}

case "${1:-status}" in
    status)
        out=$(loaded_models)
        if [[ -z "$out" ]]; then echo "GPU memory clean — no models loaded."; else echo "$out"; fi
        ;;
    evict|evict-all)
        names=$(loaded_models | awk '{print $1}')
        if [[ "$1" == "evict" && -n "$names" ]]; then
            ours=""
            while read -r name _ _ _ ctx; do
                [[ -z "$name" ]] && continue
                for mine in $OLLAMA_OUR_MODELS; do
                    if [[ "$name" == "$mine" ]]; then
                        if [[ -n "$OLLAMA_OUR_CTX" && "$ctx" != "$OLLAMA_OUR_CTX" ]]; then
                            echo "Leaving $name alone: loaded at ctx $ctx, not ours ($OLLAMA_OUR_CTX)"
                        else
                            ours="$ours $name"
                        fi
                    fi
                done
            done <<< "$(loaded_models)"
            skipped=$(comm -23 <(echo "$names" | sort) <(echo "$ours" | tr ' ' '\n' | sed '/^$/d' | sort) | tr '\n' ' ')
            [[ -n "$skipped" ]] && echo "Leaving other users' models alone: $skipped"
            names="$ours"
        fi
        if [[ -z "${names// /}" ]]; then echo "Nothing to evict."; exit 0; fi
        for name in $names; do
            echo "Evicting $name ..."
            # generate endpoint unloads chat models; embed endpoint is needed
            # for embedding-only models (generate rejects them).
            curl -sf --max-time 30 "$OLLAMA_URL/api/generate" \
                -d "{\"model\":\"$name\",\"keep_alive\":0}" > /dev/null \
            || curl -sf --max-time 30 "$OLLAMA_URL/api/embed" \
                -d "{\"model\":\"$name\",\"input\":\"x\",\"keep_alive\":0}" > /dev/null
        done
        echo "Done. Remaining:"
        loaded_models || true
        ;;
    *)
        echo "Usage: $0 [status|evict|evict-all]" >&2
        exit 1
        ;;
esac
