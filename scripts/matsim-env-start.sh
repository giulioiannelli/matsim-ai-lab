#!/usr/bin/env bash
# matsim-env-start.sh — idempotent startup for the MATSim AI Lab working environment.
#
# Usage (source, don't execute, so conda activate propagates to your shell):
#   source /path/to/matsim-ai-lab/scripts/matsim-env-start.sh
#
# What it does:
#   1. cd to the project root
#   2. activate the matsim-ai conda env
#   3. start the Qdrant vector DB container (if not already running)
#   4. verify Ollama is up and the required models are pulled
#
# Override any of these from the environment before sourcing:
#   MATSIM_CONDA_ENV, QDRANT_IMAGE, QDRANT_HTTP_PORT, QDRANT_GRPC_PORT,
#   OLLAMA_PORT, OLLAMA_MODELS (space-separated).

: "${MATSIM_CONDA_ENV:=matsim-ai}"
: "${QDRANT_IMAGE:=qdrant/qdrant}"
: "${QDRANT_HTTP_PORT:=6333}"
: "${QDRANT_GRPC_PORT:=6334}"
: "${OLLAMA_PORT:=11434}"
: "${OLLAMA_MODELS:=qwen3.5 nomic-embed-text}"

_matsim_log() { printf '[%s] %s\n' "matsim-env" "$*"; }

_matsim_project_root() {
    local src="${BASH_SOURCE[0]}"
    [ -n "$src" ] || return 1
    local dir
    dir="$(cd "$(dirname "$src")/.." >/dev/null 2>&1 && pwd)"
    printf '%s' "$dir"
}

_matsim_activate_conda() {
    local hook
    for hook in \
        "$HOME/anaconda3/etc/profile.d/conda.sh" \
        "$HOME/miniconda3/etc/profile.d/conda.sh" \
        /opt/conda/etc/profile.d/conda.sh; do
        if [ -f "$hook" ]; then
            # shellcheck source=/dev/null
            . "$hook"
            break
        fi
    done
    if ! command -v conda >/dev/null 2>&1; then
        _matsim_log "WARN: conda not found — skipping env activation"
        return 1
    fi
    conda activate "$MATSIM_CONDA_ENV"
}

_matsim_ensure_qdrant() {
    if curl -fsS --max-time 2 "http://localhost:${QDRANT_HTTP_PORT}/healthz" >/dev/null 2>&1; then
        _matsim_log "qdrant: already healthy on :${QDRANT_HTTP_PORT}"
        return 0
    fi
    if ! command -v docker >/dev/null 2>&1; then
        _matsim_log "FAIL: docker missing — install docker or start qdrant manually"
        return 1
    fi
    local existing
    existing="$(sudo docker ps -a --filter "ancestor=${QDRANT_IMAGE}" --format '{{.ID}}' | head -n1)"
    if [ -n "$existing" ]; then
        _matsim_log "qdrant: starting existing container ${existing}"
        sudo docker start "$existing" >/dev/null
    else
        _matsim_log "qdrant: creating new container"
        sudo docker run -d \
            -p "${QDRANT_HTTP_PORT}:6333" \
            -p "${QDRANT_GRPC_PORT}:6334" \
            "${QDRANT_IMAGE}" >/dev/null
    fi
    local tries=0
    until curl -fsS --max-time 2 "http://localhost:${QDRANT_HTTP_PORT}/healthz" >/dev/null 2>&1; do
        tries=$((tries + 1))
        if [ "$tries" -ge 10 ]; then
            _matsim_log "FAIL: qdrant did not become healthy"
            return 1
        fi
        sleep 1
    done
    _matsim_log "qdrant: healthy on :${QDRANT_HTTP_PORT}"
}

_matsim_check_ollama() {
    if ! curl -fsS --max-time 2 "http://localhost:${OLLAMA_PORT}/api/tags" >/dev/null 2>&1; then
        _matsim_log "FAIL: ollama not responding on :${OLLAMA_PORT} — start it with 'ollama serve' (or the ollama service)"
        return 1
    fi
    local tags_json
    tags_json="$(curl -fsS "http://localhost:${OLLAMA_PORT}/api/tags" 2>/dev/null)"
    local missing=()
    for m in $OLLAMA_MODELS; do
        if ! printf '%s' "$tags_json" | grep -q "\"${m}"; then
            missing+=("$m")
        fi
    done
    if [ "${#missing[@]}" -gt 0 ]; then
        _matsim_log "WARN: missing ollama models: ${missing[*]} — pull with 'ollama pull <model>'"
        return 1
    fi
    _matsim_log "ollama: ready (${OLLAMA_MODELS})"
}

_matsim_env_start() {
    local root
    root="$(_matsim_project_root)" || { _matsim_log "FAIL: cannot resolve project root"; return 1; }
    cd "$root" || { _matsim_log "FAIL: cannot cd to $root"; return 1; }
    _matsim_log "project: $root"
    _matsim_activate_conda && _matsim_log "conda: $MATSIM_CONDA_ENV activated"
    _matsim_ensure_qdrant
    _matsim_check_ollama
    _matsim_log "ready."
}

_matsim_env_start
