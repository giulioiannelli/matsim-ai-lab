#!/usr/bin/env bash
# Panel replanning run on a warmed ground (.claude/plans/panel-replanning-plan.md):
# the LLM is one strategy among the rule-based ones, forced on a budgeted,
# trigger-selected subset of a fixed panel each iteration. All knobs
# env-overridable; GPU eviction on ANY exit.
#
#   PLANS=output/siouxfalls-s0.10-b10-seed4711/output_plans.xml.gz CAPF=0.1 \
#   ITERS=20 PANEL=200 BUDGET=10 SEED=4721 scripts/panel-run.sh
#
# For a long run, detach it as a user unit so it survives the terminal:
#   systemd-run --user --unit=matsim-panel --working-directory=$PWD \
#     -p StandardOutput=file:$PWD/output/logs/panel.log \
#     -p StandardError=file:$PWD/output/logs/panel.err scripts/panel-run.sh
set -uo pipefail
cd "$(dirname "$0")/.."

trap 'scripts/ollama-gpu.sh evict || true' EXIT

MODEL="${MODEL:-qwen3.6:27b}"
EMBED_HOST="${EMBED_HOST-localhost}"
EMBED_PORT="${EMBED_PORT:-11435}"
EMBED_ARGS=""
[ -n "$EMBED_HOST" ] && EMBED_ARGS="--embedding-host=$EMBED_HOST --embedding-port=$EMBED_PORT"
PLANS="${PLANS:-output/siouxfalls-s0.10-b10-seed4711/output_plans.xml.gz}"
CAPF="${CAPF:-0.1}"
ITERS="${ITERS:-20}"
PANEL="${PANEL:-200}"
BUDGET="${BUDGET:-10}"
QUANTILE="${QUANTILE:-0.2}"
CAP="${CAP:-10}"
SEED="${SEED:-4721}"
EXTRA="${EXTRA:-}"

[ -f "$PLANS" ] || { echo "warmed plans not found: $PLANS (run RunSiouxFalls with --sample first)" >&2; exit 2; }

echo "=== PANEL RUN: $MODEL ${ITERS}it panel${PANEL} budget${BUDGET} q${QUANTILE} seed${SEED} ground=$PLANS cap=$CAPF ==="
./mvnw -q compile exec:java \
    -Dexec.mainClass="org.matsim.project.RunSiouxFallsLLMAgents" \
    -Dexec.args="$ITERS $MODEL --panel --num-agents=$PANEL --max-queries=$BUDGET --trigger-quantile=$QUANTILE --plans-file=$PLANS --capacity-factor=$CAPF --prompt-variant=persona --enable-comparison-tools --max-tool-iterations=$CAP --seed=$SEED $EMBED_ARGS $EXTRA"
status=$?
echo "=== PANEL RUN ENDED (exit $status) ==="
exit $status
