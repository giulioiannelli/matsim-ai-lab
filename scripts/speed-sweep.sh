#!/usr/bin/env bash
# WP3 step 6: reasoning-length sweep on the same small panel smoke. Runs the
# variants sequentially (each evicts on exit) and prints the timing split and
# success rate of each, so the largest cut that keeps quality can be chosen.
#
#   VARIANTS="free brief nothink" scripts/speed-sweep.sh
#
# Variant flags (on top of --decision-output): free = as is; brief =
# --reasoning-style=brief; nothink = --thinking=false.
set -uo pipefail
cd "$(dirname "$0")/.."
trap 'scripts/ollama-gpu.sh evict || true' EXIT

read -r -a variants <<< "${VARIANTS:-free brief nothink}"
export ITERS="${ITERS:-4}" PANEL="${PANEL:-20}" BUDGET="${BUDGET:-3}" SEED="${SEED:-4721}"
for v in "${variants[@]}"; do
    case "$v" in
        free)    extra="--decision-output" ;;
        brief)   extra="--decision-output --reasoning-style=brief" ;;
        nothink) extra="--decision-output --thinking=false" ;;
        *) echo "unknown variant $v" >&2; exit 2 ;;
    esac
    echo "=== SWEEP VARIANT $v: $extra ==="
    EXTRA="$extra" scripts/panel-run.sh || echo "=== variant $v FAILED ==="
done
echo "=== SWEEP SUMMARY ==="
for d in $(ls -dt output/siouxfalls-*-decide*-panel${PANEL}q${BUDGET} 2>/dev/null); do
    echo "--- $d"
    conda run -n matsim-ai matsim-analyze timing "$d" 2>/dev/null | tail -6
    if [ -f "$d/llm_person_stats_combined.csv" ]; then
        awk -F, 'NR>1{n++; if($13=="true")a++} END{printf "applied %d/%d\n", a, n}' "$d/llm_person_stats_combined.csv"
    fi
done
