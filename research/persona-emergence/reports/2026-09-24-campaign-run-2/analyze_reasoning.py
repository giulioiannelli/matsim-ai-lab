"""Reasoning-trace analysis for campaign run 2: what the persona cites, whether it picks the
faster option, how long it thinks, and the decision boundary of car owners on public transport.
    conda run -n matsim-ai python analyze_reasoning.py   (from this directory)"""
import json, re, collections, statistics
from pathlib import Path
import numpy as np
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[4]
D = ROOT / "output/siouxfalls-c0.10-warm-qwen3.6-27b-T0.3-N3072-reasoning-persona-cmp-oneshot-decide-brief-s4721-panel200q10"
OUT = Path(__file__).resolve().parent / "figures"
plt.rcParams.update({"font.size": 9, "axes.titlesize": 9.5, "legend.fontsize": 8, "figure.dpi": 150, "savefig.bbox": "tight",
                     "axes.spines.top": False, "axes.spines.right": False})

FACTORS = {  # category -> regex over reasoning + answer text (case-insensitive)
    "transfers": r"transfer",
    "travel time": r"minute|faster|slower|quicker|longer|shorter|takes about",
    "age": r"at my age|years old|\bmy age\b|\bat \d\d\b|i'm \d\d|older|young|elderly",
    "car ownership / rule": r"my car|have a car|no car|don't have a car|car is|parked|with me|requires|drive (out|back)|car_passenger",
    "comfort / hassle": r"exhaust|tiring|stress|hassle|comfort|relax|convenien|annoy|hopping|juggling|simpler",
    "walking distance": r"\bkm\b|kilomet|too far|long walk|walk(ing)? (nearly|almost|over|about)|distance",
    "cost / money": r"\bcost|money|fuel|\bgas\b|expensive|cheap|afford|parking fee|budget",
    "parking": r"\bpark(ing)?\b",
    "reliability / waiting": r"reliab|delay|waiting|wait for|miss(ed)? (the|a) bus|schedule",
    "health / exercise": r"exercise|health|fresh air|fitness|stretch",
    "work / employment": r"\bjob\b|not employed|retired|unemployed|get to work|my work|commute",
    "weather": r"weather|rain|cold|hot",
    "flexibility / no rush": r"no rush|not in a hurry|flexible|no fixed|at my own pace|leisure",
}

def parse_options(user):
    """trip number -> {mode: (travel time s, transfers)} and the current mode per trip."""
    opts, cur = {}, {}
    for m in re.finditer(r"- trip (\d+), \S+ -> \S+, leaving \d\d:\d\d, currently by (\w+): (.*)", user):
        n, mode, rest = int(m.group(1)), m.group(2), m.group(3)
        d = {}
        for j in re.finditer(r"\{[^{}]*\}", rest):
            o = json.loads(j.group(0))
            if o.get("feasible", True) and o.get("travelTimeSeconds") is not None: d[o["mode"]] = (float(o["travelTimeSeconds"]), int(o.get("transfers", 0)))
        opts[n], cur[n] = d, mode
    return opts, cur

rows = []
for line in open(D / "llm_chat_log_ChatLog_combined.jsonl"):
    e = json.loads(line); req = json.loads(e["requestBody"]); rb = json.loads(e["responseBody"]); msg = rb.get("message", {})
    sysm, user = req["messages"][0]["content"], req["messages"][1]["content"]
    who = sysm.split("Who you are:")[1].split("\n\n")[0] if "Who you are:" in sysm else ""
    age = int(re.search(r"(\d+)-year-old", who).group(1)); car = "have a car" in who and "don't have a car" not in who
    works = "You work" in who
    dec = None
    for tc in msg.get("tool_calls") or []:
        f = tc.get("function", tc)
        if f.get("name") == "decide_trips": dec = f["arguments"].get("decisions") or []
    opts, cur = parse_options(user)
    think = msg.get("thinking") or ""; content = msg.get("content") or ""
    rows.append(dict(age=age, car=car, works=works, dec=dec, opts=opts, cur=cur, think=think, content=content,
                     gtok=rb.get("eval_count", 0), waits=len(re.findall(r"\bWait\b", think)), pid=None))

N = {}
# ---- factors cited ----
fc = collections.Counter()
for r in rows:
    txt = (r["think"] + " " + r["content"]).lower()
    for k, rx in FACTORS.items():
        if re.search(rx, txt): fc[k] += 1
N["factors"] = dict(fc); N["n"] = len(rows)

# ---- decision quality: chosen vs fastest ----
switch_slower = switch_faster = 0; penalties = []; sw_from = collections.Counter(); sw_transfers = []
for r in rows:
    for d in r["dec"] or []:
        o = r["opts"].get(d["trip"]); c = r["cur"].get(d["trip"])
        if not o or d["mode"] not in o or c not in o: continue
        chosen, current = o[d["mode"]][0], o[c][0]; fastest = min(v[0] for v in o.values())
        penalties.append((chosen - fastest) / 60); sw_from[(c, d["mode"])] += 1
        if chosen > current + 1: switch_slower += 1
        else: switch_faster += 1
        if c == "pt": sw_transfers.append(o["pt"][1])
N["switch_to_slower_than_current"] = switch_slower; N["switch_faster_or_equal"] = switch_faster
N["penalty_vs_fastest_median_min"] = statistics.median(penalties); N["penalty_max_min"] = max(penalties)
N["switch_pairs"] = {f"{a}->{b}": v for (a, b), v in sw_from.items()}; N["switched_pt_transfers_median"] = statistics.median(sw_transfers)

# ---- car owners currently on pt: decision boundary ----
pts = []  # (pt time min, car time min, pt transfers, switched?, age)
keep_when_car_faster = 0; owners_on_pt = 0
for r in rows:
    if not r["car"]: continue
    changed = {d["trip"]: d["mode"] for d in r["dec"] or []}
    for t, c in r["cur"].items():
        if c != "pt" or "car" not in r["opts"].get(t, {}) or "pt" not in r["opts"][t]: continue
        owners_on_pt += 1
        ptt, tr = r["opts"][t]["pt"]; ct = r["opts"][t]["car"][0]
        sw = changed.get(t) == "car"; pts.append((ptt / 60, ct / 60, tr, sw, r["age"]))
        if not sw and ct < ptt: keep_when_car_faster += 1
N["owner_pt_trips"] = owners_on_pt; N["owner_pt_trips_switched"] = sum(p[3] for p in pts); N["kept_pt_though_car_faster"] = keep_when_car_faster
N["switched_though_car_slower"] = sum(1 for p in pts if p[3] and p[1] > p[0])
# car owners: every trip by current mode -> switched to car?
owner_trips = collections.defaultdict(lambda: [0, 0])
for r in rows:
    if not r["car"]: continue
    changed = {d["trip"]: d["mode"] for d in r["dec"] or []}
    for t, c in r["cur"].items():
        owner_trips[c][1] += 1; owner_trips[c][0] += changed.get(t) == "car"
N["owner_trips_by_current_mode"] = {k: f"{v[0]}/{v[1]}" for k, v in owner_trips.items()}
carless_trips = collections.defaultdict(lambda: collections.Counter())
for r in rows:
    if r["car"]: continue
    changed = {d["trip"]: d["mode"] for d in r["dec"] or []}
    for t, c in r["cur"].items(): carless_trips[c][changed.get(t, "kept")] += 1
N["carless_trips_by_current_mode"] = {k: dict(v) for k, v in carless_trips.items()}
N["penalties_positive"] = sum(p > 0.5 for p in penalties); N["penalty_mean_when_positive"] = statistics.mean([p for p in penalties if p > 0.5]) if any(p > 0.5 for p in penalties) else 0
# rate by transfers
by_tr = collections.defaultdict(lambda: [0, 0])
for p in pts: by_tr[min(p[2], 4)][1] += 1; by_tr[min(p[2], 4)][0] += p[3]
N["switch_rate_by_transfers"] = {k: f"{v[0]}/{v[1]}" for k, v in sorted(by_tr.items())}
# rate by age band and car
bands = [(0, 30), (30, 50), (50, 65), (65, 120)]
by_age = {}
for lo, hi in bands:
    sub = [r for r in rows if lo <= r["age"] < hi]
    for carflag in (True, False):
        s2 = [r for r in sub if r["car"] == carflag]
        if s2: by_age[f"{lo}-{hi} car={carflag}"] = (sum(bool(r["dec"]) for r in s2), len(s2))
N["change_by_age_car"] = {k: f"{a}/{b}" for k, (a, b) in by_age.items()}
N["queries_carless"] = sum(not r["car"] for r in rows); N["changes_carless"] = sum(bool(r["dec"]) for r in rows if not r["car"])
N["queries_car"] = sum(r["car"] for r in rows); N["changes_car"] = sum(bool(r["dec"]) for r in rows if r["car"])

# ---- reasoning length and loops ----
gk = [r["gtok"] for r in rows if not r["dec"]]; gc = [r["gtok"] for r in rows if r["dec"]]
N["tokens_keep_median"] = statistics.median(gk); N["tokens_change_median"] = statistics.median(gc)
N["waits_median"] = statistics.median(r["waits"] for r in rows); N["waits_ge3"] = sum(r["waits"] >= 3 for r in rows); N["waits_max"] = max(r["waits"] for r in rows)
N["think_chars_median"] = statistics.median(len(r["think"]) for r in rows); N["content_chars_median"] = statistics.median(len(r["content"]) for r in rows)
N["empty_content"] = sum(len(r["content"].strip()) == 0 for r in rows)
# structure: share of traces that restate the numbers (any "seconds" or "s)" arithmetic) despite the brief instruction
N["restates_numbers"] = sum(bool(re.search(r"\d{3,4}\s*(s|seconds)", r["think"])) for r in rows)

# ---- quotes ----
def first_sentences(t, n=2):
    s = re.split(r"(?<=[.!?])\s+", t.strip()); return " ".join(s[:n])
quotes = []
for r in rows:
    if r["dec"] and r["car"] and any(p for p in r["dec"] if p["mode"] == "car") and len(quotes) < 1: quotes.append(("switch to car", r["age"], first_sentences(r["content"], 3)))
for r in rows:
    if not r["dec"] and r["car"] and any(c == "pt" and "car" in r["opts"].get(t, {}) and r["opts"][t]["car"][0] < r["opts"][t]["pt"][0] for t, c in r["cur"].items()) and r["content"].strip():
        quotes.append(("kept pt although car faster", r["age"], first_sentences(r["content"], 3))); break
for r in rows:
    if not r["dec"] and not r["car"] and r["age"] >= 65 and r["content"].strip(): quotes.append(("carless, 65+", r["age"], first_sentences(r["content"], 3))); break
for r in rows:
    if r["dec"] and any(p["mode"] == "walk" for p in r["dec"]) and r["content"].strip(): quotes.append(("switch to walk", r["age"], first_sentences(r["content"], 3))); break
for r in sorted(rows, key=lambda r: -r["waits"])[:1]:
    i = r["think"].find("Wait"); quotes.append(("re-check loop (thinking)", r["age"], r["think"][max(0, i - 120):i + 260].replace("\n", " ")))
N["quotes"] = quotes

# ================= figures =================
fig, ax = plt.subplots(1, 2, figsize=(7.2, 2.5), gridspec_kw=dict(width_ratios=[1.3, 1]))
keys = [k for k, _ in fc.most_common()]; vals = [fc[k] for k in keys]
ax[0].barh(keys[::-1], vals[::-1], color="#4c72b0"); ax[0].set_xlabel("conversations mentioning it (of 250)"); ax[0].set_title("(a) what the reasoning talks about")
ax[0].tick_params(axis="y", labelsize=7.5)
sw = [p for p in pts if p[3]]; kp = [p for p in pts if not p[3]]
ax[1].scatter([p[0] for p in kp], [p[1] for p in kp], s=12, color="#b0b0b0", label=f"kept the bus ({len(kp)})")
ax[1].scatter([p[0] for p in sw], [p[1] for p in sw], s=14, color="#4c72b0", label=f"switched to car ({len(sw)})")
lim = max(max(p[0] for p in pts), max(p[1] for p in pts)) + 2
ax[1].plot([0, lim], [0, lim], color="k", lw=0.7, ls="--"); ax[1].text(lim * 0.55, lim * 0.62, "car slower", fontsize=7, rotation=38); ax[1].text(lim * 0.68, lim * 0.5, "car faster", fontsize=7, rotation=38)
ax[1].set_xlabel("bus travel time [min]"); ax[1].set_ylabel("car travel time [min]"); ax[1].legend(frameon=False, loc="upper left"); ax[1].set_title("(b) car owners' bus trips: switch or keep?")
ax[1].set_xlim(0, lim); ax[1].set_ylim(0, lim)
fig.savefig(OUT / "fig_reasoning.pdf"); plt.close(fig)

fig, ax = plt.subplots(1, 3, figsize=(7.4, 2.3), gridspec_kw=dict(wspace=0.42))
modes_ = ["pt", "walk", "car"]; tot = [owner_trips[m][1] for m in modes_]; swc = [owner_trips[m][0] for m in modes_]
ax[0].bar(modes_, tot, color="#d9d9d9", label="trips of car owners"); ax[0].bar(modes_, swc, color="#4c72b0", label="switched to car")
for i, m in enumerate(modes_): ax[0].text(i, tot[i] + 3, f"{swc[i]}/{tot[i]}" if m != "car" else f"{tot[i]} stay", ha="center", fontsize=7)
ax[0].set_xlabel("current mode of the trip"); ax[0].set_ylabel("trips"); ax[0].legend(frameon=False, fontsize=7, loc="upper left", bbox_to_anchor=(0, 0.92)); ax[0].set_title("(a) car owners: a rule, not a trade-off"); ax[0].set_ylim(0, 320)
labs = [f"{lo}-{hi if hi < 120 else ''}".rstrip("-") + ("+" if hi >= 120 else "") for lo, hi in bands]
rc = [by_age.get(f"{lo}-{hi} car=True", (0, 1)) for lo, hi in bands]; rn = [by_age.get(f"{lo}-{hi} car=False", (0, 1)) for lo, hi in bands]
x = np.arange(len(bands)); ax[1].bar(x - 0.18, [a / b * 100 for a, b in rc], 0.36, color="#4c72b0", label="has a car"); ax[1].bar(x + 0.18, [a / b * 100 for a, b in rn], 0.36, color="#b0b0b0", label="no car")
ax[1].set_xticks(x); ax[1].set_xticklabels(labs); ax[1].set_xlabel("age"); ax[1].set_ylabel("queries with a change [%]"); ax[1].legend(frameon=False); ax[1].set_title("(b) who changes"); ax[1].set_ylim(0, 100)
ax[2].hist([gk, gc], bins=np.arange(300, 1600, 100), stacked=True, color=["#b0b0b0", "#4c72b0"], label=["keep", "change"])
ax[2].set_xlabel("output tokens per query"); ax[2].set_ylabel("queries"); ax[2].legend(frameon=False); ax[2].set_title("(c) how long it thinks")
fig.savefig(OUT / "fig_who.pdf"); plt.close(fig)

json.dump(N, open(Path(__file__).resolve().parent / "numbers_reasoning.json", "w"), indent=1, default=str)
print(json.dumps({k: v for k, v in N.items() if k != "quotes"}, indent=1, default=str))
for q in quotes: print("QUOTE", q[0], q[1], "|", q[2][:400])
