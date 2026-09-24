"""Figures and numbers for the campaign-run-2 paper. Run from the repo root:
    conda run -n matsim-ai python research/persona-emergence/paper/make_figures.py
Reads the two campaign output directories and writes figures/*.pdf + numbers.json."""
import csv, gzip, json, re, subprocess, sys, collections, statistics
import xml.etree.ElementTree as ET
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent / "figures"
D2 = ROOT / "output/siouxfalls-c0.10-warm-qwen3.6-27b-T0.3-N3072-reasoning-persona-cmp-oneshot-decide-brief-s4721-panel200q10"
D1 = ROOT / "output/siouxfalls-c0.10-warm-qwen3.6-27b-T0.3-N6144-reasoning-persona-cmp-oneshot-decide-brief-s4721-panel200q10"
LOG2 = ROOT / "output/logs/campaign-2.log"
WARM = ROOT / "output/siouxfalls-s0.10-b10-seed4711"

plt.rcParams.update({"font.size": 9, "axes.titlesize": 9.5, "axes.labelsize": 9, "legend.fontsize": 8,
                     "figure.dpi": 150, "savefig.bbox": "tight", "axes.spines.top": False, "axes.spines.right": False})
C = {"load": "#c44e52", "prompt": "#dd8452", "gen": "#4c72b0", "keep": "#b0b0b0", "change": "#4c72b0",
     "car": "#4c72b0", "pt": "#55a868", "walk": "#dd8452", "never": "#8c8c8c", "drop": "#c44e52"}
N = {}

def rounds(d):
    stats = list(csv.DictReader(open(d / "llm_person_stats_combined.csv")))
    out = []
    for r, line in zip(stats, open(d / "llm_chat_log_ChatLog_combined.jsonl")):
        e = json.loads(line); rb = json.loads(e["responseBody"]); m = rb.get("message", {})
        dec = None
        for tc in m.get("tool_calls") or []:
            f = tc.get("function", tc)
            if f.get("name") == "decide_trips": dec = f["arguments"].get("decisions") or []
        out.append(dict(it=int(r["iteration"]), pid=r["personId"], wall=e["durationMs"] / 1e3,
                        load=rb.get("load_duration", 0) / 1e9, prompt=rb.get("prompt_eval_duration", 0) / 1e9,
                        gen=rb.get("eval_duration", 0) / 1e9, ptok=rb.get("prompt_eval_count", 0), gtok=rb.get("eval_count", 0),
                        dec=dec, nmsg=len(json.loads(e["requestBody"])["messages"])))
    return out

R2 = rounds(D2)
# run 1 log has 22 rounds for 20 agents (2 retries): align by order of stats is not possible -> parse rounds only
R1 = []
for line in open(D1 / "llm_chat_log_ChatLog_combined.jsonl"):
    e = json.loads(line); rb = json.loads(e["responseBody"]); m = rb.get("message", {})
    dec = None
    for tc in m.get("tool_calls") or []:
        f = tc.get("function", tc)
        if f.get("name") == "decide_trips": dec = f["arguments"].get("decisions") or []
    R1.append(dict(wall=e["durationMs"] / 1e3, load=rb.get("load_duration", 0) / 1e9, prompt=rb.get("prompt_eval_duration", 0) / 1e9,
                   gen=rb.get("eval_duration", 0) / 1e9, gtok=rb.get("eval_count", 0), dec=dec))

# ---------- numbers: protocol and timing ----------
walls = [r["wall"] for r in R2]
N["rounds"] = len(R2); N["agents_queried"] = len(R2)
N["wall_median"] = statistics.median(walls); N["wall_max"] = max(walls); N["wall_mean"] = statistics.mean(walls)
N["load_total"] = sum(r["load"] for r in R2); N["prompt_total"] = sum(r["prompt"] for r in R2); N["gen_total"] = sum(r["gen"] for r in R2)
N["llm_total"] = sum(walls); N["reload_rounds"] = sum(r["load"] > 1 for r in R2)
N["load_share"] = N["load_total"] / N["llm_total"]; N["gen_share"] = N["gen_total"] / N["llm_total"]; N["prompt_share"] = N["prompt_total"] / N["llm_total"]
noreload = [r["wall"] for r in R2 if r["load"] <= 1]; reload = [r["wall"] for r in R2 if r["load"] > 1]
N["wall_median_noreload"] = statistics.median(noreload); N["wall_median_reload"] = statistics.median(reload)
N["ptok_median"] = statistics.median(r["ptok"] for r in R2); N["gtok_median"] = statistics.median(r["gtok"] for r in R2); N["gtok_max"] = max(r["gtok"] for r in R2)
N["gen_tok_s"] = sum(r["gtok"] for r in R2) / N["gen_total"]; N["prompt_tok_s"] = sum(r["ptok"] for r in R2) / N["prompt_total"]
N["retries"] = sum(r["nmsg"] > 2 for r in R2); N["cap_hits"] = sum(r["gtok"] >= 3072 for r in R2)
stats2 = list(csv.DictReader(open(D2 / "llm_person_stats_combined.csv")))
N["applied"] = sum(r["planApplied"] == "true" for r in stats2); N["verif_fail"] = sum(int(r["toolVerificationFailures"]) for r in stats2)
# run 1
N["r1_rounds"] = len(R1); N["r1_empty"] = sum(1 for r in R1 if r["dec"] == []); N["r1_change"] = sum(1 for r in R1 if r["dec"])
N["r1_nodec"] = sum(1 for r in R1 if r["dec"] is None); N["r1_load_share"] = sum(r["load"] for r in R1) / sum(r["wall"] for r in R1)
N["r1_cap_hits"] = sum(r["gtok"] >= 6144 for r in R1); N["r1_wall_median"] = statistics.median(r["wall"] for r in R1)

# iteration wall times from the progress lines
it_wall = {}
for m in re.finditer(r"iteration (\d+)/25 done \| this (?:(\d+)h)?(\d+)m(\d+)s", open(LOG2).read()):
    it, h, mm, ss = m.groups(); it_wall[int(it)] = (int(h or 0) * 3600 + int(mm) * 60 + int(ss))
N["it_wall_median"] = statistics.median(v for k, v in it_wall.items() if k > 0)
N["run_total_min"] = sum(it_wall.values()) / 60
N["it_wall_min_noreload"] = min(v for k, v in it_wall.items() if k > 0); N["it_wall_max"] = max(it_wall.values())

# ---------- decisions ----------
by_it = collections.defaultdict(lambda: dict(keep=0, change=0))
legs = collections.Counter(); shifts = 0; changed_last = {}; changed_all = collections.defaultdict(list)
for r in R2:
    if r["dec"]: by_it[r["it"]]["change"] += 1; changed_all[r["pid"]].append(r["it"]); changed_last[r["pid"]] = r["dec"]
    else: by_it[r["it"]]["keep"] += 1
    for x in r["dec"] or []:
        legs[x["mode"]] += 1
        if x.get("departureShiftMinutes") not in (None, 0): shifts += 1
N["keep"] = sum(v["keep"] for v in by_it.values()); N["change"] = sum(v["change"] for v in by_it.values())
N["changed_agents"] = len(changed_last); N["legs"] = dict(legs); N["shifts"] = shifts
sel = list(csv.DictReader(open(D2 / "llm_panel_selection.csv")))
reasons_by_it = collections.defaultdict(collections.Counter)
for s in sel: reasons_by_it[int(s["iteration"])][s["reason"]] += 1
N["sel_reasons"] = dict(collections.Counter(s["reason"] for s in sel))
drops = [float(s["scoreDrop"]) for s in sel if s["reason"] == "SCORE_DROP"]
N["drop_median"] = statistics.median(drops); N["drop_max"] = max(drops)
seen = collections.Counter(s["personId"] for s in sel); N["agents_seen_twice"] = sum(v > 1 for v in seen.values())
# re-selected agents: did they change first time and what did they do the second time
first_dec = {}; re_behaviour = collections.Counter()
for r in R2:
    if r["pid"] not in first_dec: first_dec[r["pid"]] = bool(r["dec"])
    else: re_behaviour[("changed" if first_dec[r["pid"]] else "kept") + "->" + ("change" if r["dec"] else "keep")] += 1
N["re_behaviour"] = dict(re_behaviour)

# ---------- ground ----------
def semi(p): return list(csv.DictReader(open(p), delimiter=";"))
sc = semi(D2 / "scorestats.csv"); ms = semi(D2 / "modestats.csv")
N["score_first"] = float(sc[0]["avg_executed"]); N["score_last"] = float(sc[-1]["avg_executed"])
N["modes_first"] = {k: float(ms[0][k]) for k in ("car", "pt", "walk")}; N["modes_last"] = {k: float(ms[-1][k]) for k in ("car", "pt", "walk")}
wsc = semi(WARM / "scorestats.csv"); wms = semi(WARM / "modestats.csv")
N["warm_score_first"] = float(wsc[0]["avg_executed"]); N["warm_score_last"] = float(wsc[-1]["avg_executed"])
N["warm_modes_first"] = {k: float(wms[0][k]) for k in ("car", "pt", "walk")}
stuck = 0
with gzip.open(D2 / "output_events.xml.gz", "rb") as f:
    for ev, el in ET.iterparse(f, events=("end",)):
        if el.tag == "event" and el.get("type") == "stuckAndAbort": stuck += 1
        el.clear()
N["stuck_last"] = stuck

# ---------- survival of LLM-decided modes ----------
want = set(changed_last); plans_of = {}
with gzip.open(D2 / "output_plans.xml.gz", "rb") as f:
    for ev, el in ET.iterparse(f, events=("end",)):
        if el.tag == "person":
            if el.get("id") in want:
                pl = []
                for p in el.findall("plan"):
                    modes = [lg.get("mode") for lg in p.findall("leg")]
                    main = sorted({m for m in modes if m != "walk"} or set(modes))
                    pl.append((p.get("selected") == "yes", float(p.get("score")), main))
                plans_of[el.get("id")] = pl
            el.clear()
surv_sel = surv_best = in_mem = dropped = 0; deltas = []
for pid, dec in changed_last.items():
    modes = {x["mode"] for x in dec}
    pl = plans_of[pid]; selm = [p for p in pl if p[0]][0][2]
    has = lambda p: all(m in p[2] for m in modes)
    surv_sel += all(m in selm for m in modes)
    best = max(pl, key=lambda p: p[1]); surv_best += has(best)
    with_ = [p[1] for p in pl if has(p)]; without = [p[1] for p in pl if not has(p)]
    in_mem += bool(with_); dropped += not with_
    if with_ and without: deltas.append(max(with_) - max(without))
N["surv_selected"] = surv_sel; N["surv_best"] = surv_best; N["surv_in_memory"] = in_mem; N["surv_dropped"] = dropped
N["delta_n"] = len(deltas); N["delta_median"] = statistics.median(deltas); N["delta_neg"] = sum(d < 0 for d in deltas)
N["delta_within_half"] = sum(abs(d) < 0.5 for d in deltas)
# what do re-selected changers do the second time?
second_modes = collections.Counter(); seen_once = {}
for r in R2:
    if r["pid"] in seen_once:
        if seen_once[r["pid"]] and r["dec"]:
            second_modes[",".join(sorted({x["mode"] for x in r["dec"]}))] += 1
    else: seen_once[r["pid"]] = bool(r["dec"])
N["second_modes"] = dict(second_modes)

# ---------- persona ----------
txt = subprocess.run(["conda", "run", "-n", "matsim-ai", "matsim-analyze", "persona", str(D2)], capture_output=True, text=True).stdout
pers = re.findall(r"\[(OK |-- )\] plan_\w+@iter(\d+): first-person ([\d.]+)/100w, traits \[([^\]]*)\]", txt)
N["persona_n"] = len(pers); N["persona_ok"] = sum(p[0].strip() == "OK" for p in pers)
fp = [float(p[2]) for p in pers]; N["fp_median"] = statistics.median(fp)
traits = collections.Counter(t for p in pers for t in p[3].split(",") if t and t != "none"); N["traits"] = dict(traits)
N["loops_none"] = bool(re.search(r"Degenerate repetition loops ---\s*none detected", txt)); N["contradictions_none"] = bool(re.search(r"contradictions ---\s*none detected", txt))

# ================= figures =================
# 1 timing
fig, ax = plt.subplots(1, 2, figsize=(7.2, 2.5))
its = sorted(set(r["it"] for r in R2))
L = [sum(r["load"] for r in R2 if r["it"] == i) for i in its]; P = [sum(r["prompt"] for r in R2 if r["it"] == i) for i in its]; G = [sum(r["gen"] for r in R2 if r["it"] == i) for i in its]
ax[0].bar(its, G, color=C["gen"], label="generation"); ax[0].bar(its, P, bottom=G, color=C["prompt"], label="prompt eval")
ax[0].bar(its, L, bottom=np.array(G) + np.array(P), color=C["load"], label="model (re)load")
ax[0].set_xlabel("iteration"); ax[0].set_ylabel("LLM time per iteration [s]"); ax[0].legend(frameon=False, loc="upper left"); ax[0].set_title("(a) where the LLM time goes, 10 agents per iteration")
ax[1].hist(noreload, bins=np.arange(0, 110, 5), color=C["gen"], label=f"model resident (n={len(noreload)})")
ax[1].hist(reload, bins=np.arange(0, 110, 5), color=C["load"], alpha=0.8, label=f"reload first (n={len(reload)})")
ax[1].axvline(N["wall_median"], color="k", ls="--", lw=0.8); ax[1].text(N["wall_median"] + 1.5, ax[1].get_ylim()[1] * 0.45, f"median\n{N['wall_median']:.0f} s", fontsize=8)
ax[1].set_xlabel("wall time per agent query [s]"); ax[1].set_ylabel("queries"); ax[1].legend(frameon=False, loc="upper right"); ax[1].set_title("(b) one query = one LLM round")
fig.savefig(OUT / "fig_timing.pdf"); plt.close(fig)

# 2 iteration wall time
fig, ax = plt.subplots(figsize=(7.2, 2.1))
xs = sorted(it_wall); ys = [it_wall[i] / 60 for i in xs]; llm = [sum(r["wall"] for r in R2 if r["it"] == i) / 60 for i in xs]
ax.bar(xs, ys, color="#d9d9d9", label="iteration wall time"); ax.bar(xs, llm, color=C["gen"], label="of which LLM queries")
ax.bar(xs, [sum(r["load"] for r in R2 if r["it"] == i) / 60 for i in xs], color=C["load"], label="of which model reloads")
ax.set_xlabel("iteration"); ax.set_ylabel("minutes"); ax.legend(frameon=False, ncol=3, loc="upper left"); ax.set_ylim(0, 11)
ax.set_title("Iteration wall time: reload-free iterations take about 5.5 min, colleague-induced reloads add 3.5 min")
fig.savefig(OUT / "fig_iterations.pdf"); plt.close(fig)

# 3 decisions and selection
fig, ax = plt.subplots(1, 3, figsize=(7.2, 2.4), gridspec_kw=dict(width_ratios=[2.4, 1, 1.1], wspace=0.45))
its = sorted(by_it); k = [by_it[i]["keep"] for i in its]; c = [by_it[i]["change"] for i in its]
ax[0].bar(its, k, color=C["keep"], label="keep the day"); ax[0].bar(its, c, bottom=k, color=C["change"], label="change a trip")
for i in its:
    if reasons_by_it[i]["SCORE_DROP"]: ax[0].plot(i, 10.4, marker="v", color=C["drop"], ms=4, ls="")
ax[0].text(19.2, 11.3, "selected by score drop", fontsize=7, color=C["drop"]); ax[0].set_ylim(0, 14)
ax[0].set_xlabel("iteration"); ax[0].set_ylabel("agents queried"); ax[0].legend(frameon=False, loc="upper left", ncol=2); ax[0].set_title("(a) decisions per iteration")
ax[1].bar(["car", "pt", "walk"], [legs["car"], legs["pt"], legs["walk"]], color=[C["car"], C["pt"], C["walk"]])
ax[1].set_ylabel("trips switched to"); ax[1].set_title("(b) new mode")
labels = ["never\nreviewed", "score\ndrop", "stuck"]; vals = [N["sel_reasons"].get("NEVER_REVIEWED", 0), N["sel_reasons"].get("SCORE_DROP", 0), N["sel_reasons"].get("STUCK", 0)]
ax[2].bar(labels, vals, color=[C["never"], C["drop"], "k"]); ax[2].set_ylabel("selections"); ax[2].set_title("(c) selection reason"); ax[2].tick_params(axis="x", labelsize=7.5)
fig.savefig(OUT / "fig_decisions.pdf"); plt.close(fig)

# 4 ground
fig, ax = plt.subplots(1, 2, figsize=(7.2, 2.3))
x = [int(r["iteration"]) for r in sc]
ax[0].plot(x, [float(r["avg_best"]) for r in sc], color="#55a868", label="best plan"); ax[0].plot(x, [float(r["avg_executed"]) for r in sc], color="k", label="executed plan")
ax[0].plot(x, [float(r["avg_worst"]) for r in sc], color="#c44e52", label="worst plan"); ax[0].set_xlabel("iteration"); ax[0].set_ylabel("mean score (utils)")
ax[0].legend(frameon=False, ncol=3, loc="upper center", bbox_to_anchor=(0.5, -0.28), columnspacing=1.0); ax[0].set_title("(a) population scores, 8,460 agents"); ax[0].set_ylim(19.8, 20.5)
for mode in ("car", "pt", "walk"): ax[1].plot([int(r["iteration"]) for r in ms], [100 * float(r[mode]) for r in ms], color=C[mode], label=mode)
ax[1].set_xlabel("iteration"); ax[1].set_ylabel("share of trips [%]"); ax[1].legend(frameon=False); ax[1].set_title("(b) mode shares, whole population"); ax[1].set_ylim(0, 70)
fig.savefig(OUT / "fig_ground.pdf"); plt.close(fig)

# 5 survival
fig, ax = plt.subplots(1, 2, figsize=(7.2, 2.3), gridspec_kw=dict(width_ratios=[1.25, 1.35], wspace=0.3))
cats = ["changed", "plan still\nin memory", "plan is\nbest", "plan\nselected"]; cv = [N["changed_agents"], in_mem, surv_best, surv_sel]
ax[0].bar(cats, cv, color=[C["change"], "#8172b2", "#55a868", "k"])
for i, v in enumerate(cv): ax[0].text(i, v + 0.8, str(v), ha="center", fontsize=8)
ax[0].tick_params(axis="x", labelsize=7.5)
ax[0].set_ylabel("agents"); ax[0].set_title("(a) at the end of the run"); ax[0].set_ylim(0, 50)
ax[1].hist(deltas, bins=np.arange(-3, 3.01, 0.25), color="#8172b2"); ax[1].axvline(0, color="k", lw=0.8)
ax[1].set_xlabel("best score with the LLM's modes minus best without [utils]"); ax[1].set_ylabel("agents")
ax[1].set_title(f"(b) agents holding both kinds of plan (n={len(deltas)}): median difference {N['delta_median']:+.2f}", fontsize=8.5)
fig.savefig(OUT / "fig_survival.pdf"); plt.close(fig)

# 6 persona
fig, ax = plt.subplots(1, 2, figsize=(7.2, 2.2))
ok = [float(p[2]) for p in pers if p[0].strip() == "OK"]; no = [float(p[2]) for p in pers if p[0].strip() != "OK"]
ax[0].hist([ok, no], bins=np.arange(0, 6.5, 0.4), stacked=True, color=["#55a868", "#b0b0b0"], label=[f"persona emerged ({len(ok)})", f"not emerged ({len(no)})"])
ax[0].set_xlabel("first-person markers per 100 words"); ax[0].set_ylabel("conversations"); ax[0].legend(frameon=False); ax[0].set_title("(a) first-person voice")
tk = ["age", "car_ownership", "employment"]; ax[1].bar([t.replace("_", "\n") for t in tk], [traits.get(t, 0) for t in tk], color="#55a868")
ax[1].set_ylabel("conversations citing the trait"); ax[1].set_title("(b) traits grounded in the reasoning"); ax[1].set_ylim(0, 260)
fig.savefig(OUT / "fig_persona.pdf"); plt.close(fig)

# 7 run 1 vs run 2 on the same ten agents (iteration 1)
fig, ax = plt.subplots(figsize=(3.6, 2.1))
r2_it1 = [r for r in R2 if r["it"] == 1]
vals = [[N["r1_empty"] - 0, N["r1_change"]], [sum(1 for r in r2_it1 if r["dec"] == []), sum(1 for r in r2_it1 if r["dec"])]]
ax.bar([0, 1], [vals[0][0], vals[1][0]], color=C["keep"], label="keep"); ax.bar([0, 1], [vals[0][1], vals[1][1]], bottom=[vals[0][0], vals[1][0]], color=C["change"], label="change")
ax.set_xticks([0, 1]); ax.set_xticklabels(["per-place modes\n(run 1)", "tour-level vehicles\n(run 2)"]); ax.set_ylabel("decisions"); ax.legend(frameon=False)
ax.set_title("Same seed, same first agents")
fig.savefig(OUT / "fig_context_fix.pdf"); plt.close(fig)

json.dump(N, open(OUT.parent / "numbers.json", "w"), indent=1, default=float)
print(json.dumps(N, indent=1, default=float))
