#!/usr/bin/env bash
# 3-seed A/B for Phase 4: persona-only vs persona + comparison tools.
# 1 iter, num-agents=5, Sioux Falls, qwen3.5. Each run writes to its own
# suffixed output/ dir and appends its summary to output/phase4_sweep.log.

set -u
cd "$(dirname "$0")/.."

SEEDS=(1 2 3)
MODEL=qwen3.5
AGENTS=5
ITERS=1
LOG=output/phase4_sweep.log

mkdir -p output
: > "$LOG"

run_one() {
  local seed=$1
  local arm=$2
  local flags=$3
  local tag="seed=$seed arm=$arm"
  echo "=== START $tag ($(date -Iseconds)) ===" | tee -a "$LOG"
  # shellcheck disable=SC2086
  ./mvnw -q exec:java \
    -Dexec.mainClass="org.matsim.project.RunSiouxFallsLLMAgents" \
    -Dexec.args="$ITERS $MODEL --prompt-variant=persona --num-agents=$AGENTS --seed=$seed $flags" \
    >>"$LOG" 2>&1
  echo "=== END   $tag ($(date -Iseconds)) exit=$? ===" | tee -a "$LOG"
}

for s in "${SEEDS[@]}"; do
  run_one "$s" "persona-only" ""
  run_one "$s" "persona-cmp"  "--enable-comparison-tools"
done

echo "all runs complete" | tee -a "$LOG"
