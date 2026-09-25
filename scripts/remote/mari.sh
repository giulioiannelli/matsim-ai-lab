#!/usr/bin/env bash
# Run MATSim on the GPU server so a run survives the laptop closing.
#
#   scripts/remote/mari.sh deploy                 # build fat jar (if stale) and sync jar, profiles, ground, scripts
#   NAME=r3 ITERS=5 EXTRA="--decision-output --reasoning-style=brief" scripts/remote/mari.sh run
#   scripts/remote/mari.sh status [NAME]          # tmux sessions, progress.txt of running outputs, GPU models
#   scripts/remote/mari.sh log NAME               # last lines of the run log
#   scripts/remote/mari.sh fetch NAME             # copy that run's output dir back to output/
#   scripts/remote/mari.sh stop NAME              # stop a run (its trap evicts our models)
#
# Runs live in tmux session "run-NAME" under ~/matsim-ai-lab on the server; services
# (Qdrant, CPU embedder) in tmux session "matsim-svc" (bin/services.sh there).
set -euo pipefail
cd "$(dirname "$0")/../.."
HOST="${MARI_HOST:-ollama-server@mari.dinfo.unifi.it}"; PORT="${MARI_PORT:-25746}"; RDIR="matsim-ai-lab"
SSH=(ssh -p "$PORT" -o BatchMode=yes "$HOST"); RSYNC=(rsync -az -e "ssh -p $PORT -o BatchMode=yes")
JAR=matsim-ai-lab-0.0.1-SNAPSHOT.jar
case "${1:-status}" in
  deploy)
    if [[ ! -f $JAR || -n "$(find src/main -newer $JAR -type f | head -n 1)" ]]; then ./mvnw -q clean package -DskipTests; fi
    "${RSYNC[@]}" "$JAR" "$HOST:$RDIR/bin/"
    "${RSYNC[@]}" config/model-profiles.yaml "$HOST:$RDIR/config/"
    "${RSYNC[@]}" scripts/remote/panel-run-jar.sh "$HOST:$RDIR/bin/"
    "${RSYNC[@]}" output/siouxfalls-s0.10-b10-seed4711/output_plans.xml.gz "$HOST:$RDIR/ground/siouxfalls-s0.10-b10-seed4711.plans.xml.gz"
    "${SSH[@]}" "cd $RDIR && bin/services.sh start && bin/services.sh status"
    echo "deployed $(git rev-parse --short HEAD)$(git diff --quiet src || echo +dirty)" ;;
  run)
    NAME="${NAME:?set NAME}"
    envs=""; for v in MODEL PLANS CAPF ITERS PANEL BUDGET QUANTILE CAP SEED EXTRA HEAP; do [[ -n "${!v:-}" ]] && envs+="$v=$(printf '%q' "${!v}") "; done
    "${SSH[@]}" "cd $RDIR && bin/services.sh start >/dev/null && tmux new-session -d -s run-$NAME \"$envs bin/panel-run-jar.sh 2>&1 | tee -a output/logs/$NAME.log\" && echo started run-$NAME" ;;
  status)
    "${SSH[@]}" "cd $RDIR && tmux ls 2>/dev/null; for f in \$(ls -td output/*/progress.txt 2>/dev/null | head -n 3); do echo \"\$f: \$(tail -n 1 \$f)\"; done; curl -s -m 5 127.0.0.1:11434/api/ps | python3 -c 'import json,sys; print(\"GPU:\", [(m[\"name\"], m.get(\"context_length\")) for m in json.load(sys.stdin)[\"models\"]])'" ;;
  log) "${SSH[@]}" "tail -n ${LINES:-20} $RDIR/output/logs/${2:?NAME}.log" ;;
  fetch)
    d=$("${SSH[@]}" "cd $RDIR && grep -o 'output/[^ ]*' output/logs/${2:?NAME}.log | grep -v logs | head -n 1" || true)
    d=${d:-$("${SSH[@]}" "cd $RDIR && ls -td output/siouxfalls* | head -n 1")}
    "${RSYNC[@]}" --exclude 'ITERS/it.*/*.events.xml.gz' "$HOST:$RDIR/$d" output/ && echo "fetched $d" ;;
  stop) "${SSH[@]}" "tmux send-keys -t run-${2:?NAME} C-c; sleep 5; tmux kill-session -t run-$2 2>/dev/null; echo stopped run-$2" ;;
  *) sed -n 2,13p "$0"; exit 1 ;;
esac
