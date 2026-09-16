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
#   4. open the SSH tunnel to the remote Ollama GPU server (if not already open);
#      stops the local ollama service first if it holds the port
#   5. verify Ollama is up and the required models are pulled
#   6. start a second, local Ollama for embeddings (so the remote chat server
#      never has to swap the chat model out for the embedder); pulls the
#      embedding model locally if missing
#
# Override any of these from the environment before sourcing:
#   MATSIM_CONDA_ENV, QDRANT_IMAGE, QDRANT_HTTP_PORT, QDRANT_GRPC_PORT,
#   OLLAMA_PORT, OLLAMA_MODELS (space-separated),
#   OLLAMA_TUNNEL (1=remote via ssh tunnel, 0=use local ollama),
#   OLLAMA_REMOTE_HOST, OLLAMA_REMOTE_SSH_PORT, OLLAMA_REMOTE_USER, OLLAMA_REMOTE_PORT,
#   LOCAL_EMBEDDER (1=start local embedding server, 0=skip), LOCAL_EMBED_PORT,
#   LOCAL_EMBED_MODEL, LOCAL_EMBED_LOG.
#
# Runners then take:  --embedding-host=localhost --embedding-port=$LOCAL_EMBED_PORT

: "${MATSIM_CONDA_ENV:=matsim-ai}"
: "${QDRANT_IMAGE:=qdrant/qdrant}"
: "${QDRANT_HTTP_PORT:=6333}"
: "${QDRANT_GRPC_PORT:=6334}"
: "${OLLAMA_PORT:=11434}"
: "${OLLAMA_MODELS:=qwen3.5:9b qwen3.6:27b}"
: "${OLLAMA_TUNNEL:=1}"
: "${OLLAMA_REMOTE_HOST:=mari.dinfo.unifi.it}"
: "${OLLAMA_REMOTE_SSH_PORT:=25746}"
: "${OLLAMA_REMOTE_USER:=ollama-server}"
: "${OLLAMA_REMOTE_PORT:=11434}"
: "${LOCAL_EMBEDDER:=1}"
: "${LOCAL_EMBED_PORT:=11435}"
: "${LOCAL_EMBED_MODEL:=qwen3-embedding:0.6b}"
: "${LOCAL_EMBED_LOG:=/tmp/ollama-embed-${LOCAL_EMBED_PORT}.log}"

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

_matsim_ensure_tunnel() {
    if [ "$OLLAMA_TUNNEL" != "1" ]; then
        _matsim_log "tunnel: disabled (OLLAMA_TUNNEL=${OLLAMA_TUNNEL}) — using local ollama"
        return 0
    fi
    local fwd="${OLLAMA_PORT}:localhost:${OLLAMA_REMOTE_PORT}"
    local target="${OLLAMA_REMOTE_USER}@${OLLAMA_REMOTE_HOST}"
    if pgrep -f "ssh .*-L ${fwd} ${target}" >/dev/null 2>&1; then
        _matsim_log "tunnel: already open to ${OLLAMA_REMOTE_HOST} on :${OLLAMA_PORT}"
        return 0
    fi
    if ss -ltn "sport = :${OLLAMA_PORT}" 2>/dev/null | grep -q LISTEN; then
        if systemctl is-active --quiet ollama 2>/dev/null; then
            _matsim_log "tunnel: local ollama service holds :${OLLAMA_PORT} — stopping it"
            sudo systemctl stop ollama || { _matsim_log "FAIL: could not stop local ollama"; return 1; }
        else
            _matsim_log "FAIL: :${OLLAMA_PORT} is busy and not the ollama service — free it or set OLLAMA_TUNNEL=0"
            return 1
        fi
    fi
    _matsim_log "tunnel: opening ${target}:${OLLAMA_REMOTE_SSH_PORT} -> localhost:${OLLAMA_PORT}"
    ssh -N -f -p "$OLLAMA_REMOTE_SSH_PORT" \
        -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
        -L "$fwd" "$target" || { _matsim_log "FAIL: ssh tunnel did not open"; return 1; }
    _matsim_log "tunnel: open"
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

_matsim_ensure_local_embedder() {
    if [ "$LOCAL_EMBEDDER" != "1" ]; then
        _matsim_log "embedder: local server disabled (LOCAL_EMBEDDER=${LOCAL_EMBEDDER})"
        return 0
    fi
    if ! command -v ollama >/dev/null 2>&1; then
        _matsim_log "WARN: ollama binary not found — cannot start local embedder"
        return 1
    fi
    local url="http://localhost:${LOCAL_EMBED_PORT}"
    if ! curl -fsS --max-time 2 "$url/api/tags" >/dev/null 2>&1; then
        _matsim_log "embedder: starting local ollama on :${LOCAL_EMBED_PORT} (log: ${LOCAL_EMBED_LOG})"
        OLLAMA_HOST="127.0.0.1:${LOCAL_EMBED_PORT}" setsid nohup ollama serve >"$LOCAL_EMBED_LOG" 2>&1 </dev/null &
        local tries=0
        until curl -fsS --max-time 2 "$url/api/tags" >/dev/null 2>&1; do
            tries=$((tries + 1))
            if [ "$tries" -ge 20 ]; then
                _matsim_log "FAIL: local embedder did not come up on :${LOCAL_EMBED_PORT}"
                return 1
            fi
            sleep 1
        done
    fi
    if ! curl -fsS "$url/api/tags" 2>/dev/null | grep -q "\"${LOCAL_EMBED_MODEL}\""; then
        _matsim_log "embedder: pulling ${LOCAL_EMBED_MODEL} locally"
        curl -fsS --max-time 900 "$url/api/pull" -d "{\"model\":\"${LOCAL_EMBED_MODEL}\",\"stream\":false}" >/dev/null \
            || { _matsim_log "FAIL: could not pull ${LOCAL_EMBED_MODEL}"; return 1; }
    fi
    _matsim_log "embedder: ${LOCAL_EMBED_MODEL} ready on :${LOCAL_EMBED_PORT}  (--embedding-host=localhost --embedding-port=${LOCAL_EMBED_PORT})"
}

_matsim_env_start() {
    local root
    root="$(_matsim_project_root)" || { _matsim_log "FAIL: cannot resolve project root"; return 1; }
    cd "$root" || { _matsim_log "FAIL: cannot cd to $root"; return 1; }
    _matsim_log "project: $root"
    _matsim_activate_conda && _matsim_log "conda: $MATSIM_CONDA_ENV activated"
    _matsim_ensure_qdrant
    _matsim_ensure_tunnel
    _matsim_check_ollama
    _matsim_ensure_local_embedder
    _matsim_log "ready."
}

_matsim_env_start
