#!/usr/bin/env python3
"""Phase 4 A/B analysis across the 3-seed sweep.

Reads the 6 run directories produced by `scripts/phase4_validation_sweep.sh`,
extracts per-agent stats + tool-call breakdowns, and prints a comparison
table grouped by arm (persona-only vs persona+cmp).

No external deps — just stdlib.
"""
from __future__ import annotations

import csv
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from statistics import mean

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "output"
SEEDS = [1, 2, 3]

FIRST_PERSON = re.compile(r"\b(I|I'm|I'd|I've|I'll|my|mine|myself|me)\b", re.I)
THIRD_PERSON = re.compile(r"\b(the user|the person|the traveler|this person|he |she |they )\b", re.I)

COMPARISON_RE = re.compile(
    r"\b(faster|slower|cheaper|shorter|longer\s+than|better\s+than|worse\s+than|instead of|rather than|compared to|prefer(?:ence)?)\b",
    re.I)
ATTRIBUTE_RE = re.compile(
    r"\b(my (?:age|car|license|bike|job|work|commute|kids|family)|"
    r"working today|have a (?:car|license|bike)|don'?t have (?:a )?(?:car|license)|"
    r"as a (?:man|woman|driver|commuter)|"
    r"I (?:work|drive|take the (?:bus|car|bike|train)|commute|usually|always|never)|"
    r"I'?m (?:not )?employed|since I'?m)\b",
    re.I)
CONSTRAINT_RE = re.compile(
    r"\b(too (?:long|short|far|slow|fast|early|late)|"
    r"(?:no more than|at least|under|over|longer than|shorter than) \d+\s*(?:min|minutes?|hours?|km)|"
    r"don'?t want to spend|hate (?:driving|waiting|parking)|tired of)\b",
    re.I)
COUNTERFACTUAL_RE = re.compile(
    r"\b(I'?d (?:rather|prefer)|I would (?:rather|prefer|like)|"
    r"instead of \w+ing|if I (?:take|use|go|had|have)|otherwise I)\b",
    re.I)


@dataclass
class RunStats:
    arm: str
    seed: int
    dir: Path
    csv_rows: list[dict] = field(default_factory=list)
    tool_counts: Counter = field(default_factory=Counter)
    agents_seen: set = field(default_factory=set)
    total_rounds: int = 0
    reasoning_total_chars: int = 0
    fp_hits: int = 0
    tp_hits: int = 0
    rubric_hits: Counter = field(default_factory=Counter)  # per-round axis hits
    rubric_meaningful: int = 0

    @property
    def agents(self) -> int:
        return len(self.csv_rows)

    @property
    def success_rate(self) -> float:
        if not self.csv_rows:
            return 0.0
        ok = sum(1 for r in self.csv_rows if r.get("success") == "true")
        return ok / len(self.csv_rows)

    @property
    def mean_tool_calls(self) -> float:
        if not self.csv_rows:
            return 0.0
        return mean(int(r.get("totalToolCalls") or 0) for r in self.csv_rows)

    @property
    def mean_cmp_calls(self) -> float:
        if not self.csv_rows:
            return 0.0
        return mean(int(r.get("comparisonToolInvocations") or 0) for r in self.csv_rows)

    @property
    def cmp_adoption(self) -> float:
        """Fraction of agents who called any comparison tool."""
        if not self.csv_rows:
            return 0.0
        hit = sum(1 for r in self.csv_rows if int(r.get("comparisonToolInvocations") or 0) > 0)
        return hit / len(self.csv_rows)

    @property
    def voice_ratio(self) -> float:
        total = self.fp_hits + self.tp_hits
        return self.fp_hits / total if total else 0.0

    def rubric_rate(self, axis: str) -> float:
        if not self.total_rounds:
            return 0.0
        return self.rubric_hits[axis] / self.total_rounds


def dir_for(arm: str, seed: int) -> Path:
    suffix = "-cmp" if arm == "persona-cmp" else ""
    return OUT / f"siouxfalls-qwen3.5-T0.3-N4096-reasoning-persona{suffix}-s{seed}"


def load_csv(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open() as f:
        return list(csv.DictReader(f))


def scan_jsonl(path: Path, stats: RunStats) -> None:
    if not path.exists():
        return
    with path.open() as f:
        for line in f:
            try:
                e = json.loads(line)
                resp = json.loads(e.get("responseBody") or "{}")
                msg = resp.get("message") or (
                    resp.get("choices", [{}])[0].get("message") if resp.get("choices") else {}
                ) or {}
            except Exception:
                continue
            stats.total_rounds += 1
            for tc in msg.get("tool_calls") or []:
                n = (tc.get("function") or {}).get("name") or tc.get("name")
                if n:
                    stats.tool_counts[n] += 1
            reasoning = msg.get("thinking") or msg.get("reasoning") or ""
            stats.reasoning_total_chars += len(reasoning)
            stats.fp_hits += len(FIRST_PERSON.findall(reasoning))
            stats.tp_hits += len(THIRD_PERSON.findall(reasoning))
            axis_hits = {
                "comparison": bool(COMPARISON_RE.search(reasoning)),
                "attribute": bool(ATTRIBUTE_RE.search(reasoning)),
                "constraint": bool(CONSTRAINT_RE.search(reasoning)),
                "counterfactual": bool(COUNTERFACTUAL_RE.search(reasoning)),
            }
            for k, v in axis_hits.items():
                if v:
                    stats.rubric_hits[k] += 1
            if sum(1 for v in axis_hits.values() if v) >= 2:
                stats.rubric_meaningful += 1


def collect() -> list[RunStats]:
    runs: list[RunStats] = []
    for seed in SEEDS:
        for arm in ("persona-only", "persona-cmp"):
            d = dir_for(arm, seed)
            s = RunStats(arm=arm, seed=seed, dir=d)
            if not d.exists():
                runs.append(s)
                continue
            s.csv_rows = load_csv(d / "llm_person_stats_combined.csv")
            scan_jsonl(d / "llm_chat_log_ChatLog_combined.jsonl", s)
            runs.append(s)
    return runs


def summarise_arm(runs: list[RunStats], arm: str) -> dict:
    arm_runs = [r for r in runs if r.arm == arm and r.dir.exists()]
    if not arm_runs:
        return {"arm": arm, "n": 0}
    return {
        "arm": arm,
        "n": len(arm_runs),
        "agents_per_run_mean": mean(r.agents for r in arm_runs),
        "success_rate": mean(r.success_rate for r in arm_runs if r.agents) if any(r.agents for r in arm_runs) else 0.0,
        "mean_tool_calls": mean(r.mean_tool_calls for r in arm_runs if r.agents) if any(r.agents for r in arm_runs) else 0.0,
        "mean_cmp_calls": mean(r.mean_cmp_calls for r in arm_runs if r.agents) if any(r.agents for r in arm_runs) else 0.0,
        "cmp_adoption": mean(r.cmp_adoption for r in arm_runs if r.agents) if any(r.agents for r in arm_runs) else 0.0,
        "voice_ratio": mean(r.voice_ratio for r in arm_runs if r.total_rounds) if any(r.total_rounds for r in arm_runs) else 0.0,
        "reasoning_chars_mean": mean(r.reasoning_total_chars for r in arm_runs if r.total_rounds) if any(r.total_rounds for r in arm_runs) else 0.0,
        "rubric_comparison": mean(r.rubric_rate("comparison") for r in arm_runs if r.total_rounds) if any(r.total_rounds for r in arm_runs) else 0.0,
        "rubric_attribute": mean(r.rubric_rate("attribute") for r in arm_runs if r.total_rounds) if any(r.total_rounds for r in arm_runs) else 0.0,
        "rubric_constraint": mean(r.rubric_rate("constraint") for r in arm_runs if r.total_rounds) if any(r.total_rounds for r in arm_runs) else 0.0,
        "rubric_counterfactual": mean(r.rubric_rate("counterfactual") for r in arm_runs if r.total_rounds) if any(r.total_rounds for r in arm_runs) else 0.0,
        "rubric_meaningful_rate": (
            sum(r.rubric_meaningful for r in arm_runs) / sum(r.total_rounds for r in arm_runs)
            if sum(r.total_rounds for r in arm_runs) else 0.0
        ),
        "tool_counts": sum((r.tool_counts for r in arm_runs), Counter()),
    }


def print_per_run(runs: list[RunStats]) -> None:
    print("\n=== per-run ===")
    hdr = f"{'arm':14s} {'seed':>4s} {'agents':>6s} {'rounds':>6s} {'success':>8s} {'toolCalls':>9s} {'cmp/agt':>7s} {'cmpAdopt':>8s} {'voice1P':>7s}  tools"
    print(hdr)
    for r in runs:
        if not r.dir.exists():
            print(f"{r.arm:14s} {r.seed:4d}   (missing)  {r.dir.name}")
            continue
        tools = ",".join(f"{k}:{v}" for k, v in sorted(r.tool_counts.items(), key=lambda x: -x[1]))
        print(f"{r.arm:14s} {r.seed:4d} {r.agents:6d} {r.total_rounds:6d} "
              f"{r.success_rate:8.2f} {r.mean_tool_calls:9.1f} {r.mean_cmp_calls:7.1f} "
              f"{r.cmp_adoption:8.2f} {r.voice_ratio:7.2f}  {tools}")


def print_arm_summary(runs: list[RunStats]) -> None:
    a = summarise_arm(runs, "persona-only")
    b = summarise_arm(runs, "persona-cmp")
    if a["n"] == 0 or b["n"] == 0:
        print("\n(insufficient data for arm summary — need at least one completed run per arm)")
        return
    print("\n=== arm summary (averaged across seeds) ===")
    rows = [
        ("agents_per_run_mean",        a["agents_per_run_mean"],        b["agents_per_run_mean"]),
        ("success_rate",               a["success_rate"],               b["success_rate"]),
        ("mean_tool_calls_per_agent",  a["mean_tool_calls"],            b["mean_tool_calls"]),
        ("mean_cmp_calls_per_agent",   a["mean_cmp_calls"],             b["mean_cmp_calls"]),
        ("cmp_adoption_rate",          a["cmp_adoption"],               b["cmp_adoption"]),
        ("voice_1P_ratio",             a["voice_ratio"],                b["voice_ratio"]),
        ("reasoning_chars_mean",       a["reasoning_chars_mean"],       b["reasoning_chars_mean"]),
        ("rubric_comparison",          a["rubric_comparison"],          b["rubric_comparison"]),
        ("rubric_attribute",           a["rubric_attribute"],           b["rubric_attribute"]),
        ("rubric_constraint",          a["rubric_constraint"],          b["rubric_constraint"]),
        ("rubric_counterfactual",      a["rubric_counterfactual"],      b["rubric_counterfactual"]),
        ("rubric_meaningful_rate",     a["rubric_meaningful_rate"],     b["rubric_meaningful_rate"]),
    ]
    print(f"{'metric':30s} {'persona':>10s} {'+cmp':>10s}  delta")
    for name, pa, pb in rows:
        d = pb - pa
        print(f"{name:30s} {pa:10.3f} {pb:10.3f}  {'+' if d >= 0 else ''}{d:.3f}")


def main() -> int:
    runs = collect()
    if not any(r.dir.exists() for r in runs):
        print("no output directories found matching pattern siouxfalls-...-reasoning-persona[-cmp]-s{1,2,3}")
        return 1
    print_per_run(runs)
    print_arm_summary(runs)
    return 0


if __name__ == "__main__":
    sys.exit(main())
