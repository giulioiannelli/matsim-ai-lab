#!/usr/bin/env bash
# WS-0 rebaseline batch (research/persona-emergence/README.md): sequential
# multi-seed persona runs with an early-stop gate between seeds and GPU
# eviction after every run. All knobs env-overridable.
#
#   MODEL=qwen3.6:27b SEEDS="4711 4712 4713" scripts/ws0-rebaseline.sh
#
# Embeddings go to a separate local Ollama by default (EMBED_HOST/EMBED_PORT,
# started by scripts/matsim-env-start.sh) so the remote chat server never
# swaps the chat model out for the embedder. EMBED_HOST="" = same server.
set -uo pipefail
cd "$(dirname "$0")/.."

MODEL="${MODEL:-qwen3.6:27b}"
EMBED_HOST="${EMBED_HOST-localhost}"
EMBED_PORT="${EMBED_PORT:-11435}"
EMBED_ARGS=""
[ -n "$EMBED_HOST" ] && EMBED_ARGS="--embedding-host=$EMBED_HOST --embedding-port=$EMBED_PORT"
ITERS="${ITERS:-10}"
AGENTS="${AGENTS:-5}"
CAP="${CAP:-10}"
MIN_SUCCESS="${MIN_SUCCESS:-0.60}"
read -r -a seeds <<< "${SEEDS:-4711 4712 4713}"

for seed in "${seeds[@]}"; do
    echo "=== SEED $seed BEGINS: $MODEL ${ITERS}it ${AGENTS}ag cap${CAP} ==="
    ./mvnw -q compile exec:java \
        -Dexec.mainClass="org.matsim.project.RunSiouxFallsLLMAgents" \
        -Dexec.args="$ITERS $MODEL --num-agents=$AGENTS --prompt-variant=persona --enable-comparison-tools --max-tool-iterations=$CAP --seed=$seed $EMBED_ARGS"
    status=$?
    scripts/ollama-gpu.sh evict || true
    if [ "$status" -ne 0 ]; then
        echo "=== SEED $seed FAILED (exit $status) — batch aborted ==="
        exit 1
    fi

    dir=$(ls -dt output/siouxfalls-*-s"$seed" 2>/dev/null | head -1)
    rate=$(python3 -c "
import csv, sys
rows = list(csv.DictReader(open(sys.argv[1])))
ok = sum(1 for r in rows if r['success'].strip().lower() == 'true')
print(f'{ok/len(rows):.3f}' if rows else '0.0')
" "$dir/llm_person_stats_combined.csv" 2>/dev/null || echo "0.0")
    echo "=== SEED $seed ENDS: success rate $rate ($dir) ==="

    if python3 -c "import sys; sys.exit(1 if float('$rate') >= float('$MIN_SUCCESS') else 0)"; then
        echo "=== EARLY STOP: $rate < $MIN_SUCCESS — remaining seeds cancelled ==="
        exit 2
    fi
done
echo "=== BATCH COMPLETE ==="
