"""Replay logged agent requests against an Ollama server under different settings and
measure latency and decision agreement, without running MATSim.

    conda run -n matsim-ai python scripts/bench/replay_bench.py <run-dir> <out-dir> \
        --variants ref,nothink,nothink_shared,prefix_nothink,9b_brief,9b_nothink --per-stratum 10

Agents are sampled from the run's first-time queries in three strata (car owner currently
on pt, car owner currently by car, no car). Each variant rewrites model / think / options /
message layout of the logged request body. Decisions are the decide_trips call, normalised
to a set of (trip, mode). Results: <out>/results.jsonl (one line per request) and
<out>/summary.json. Models this script loads are unloaded at the end (same name and context
only; instances loaded by other users are left alone).
"""
import argparse, json, random, re, statistics, sys, time, urllib.request
from collections import Counter, defaultdict
from pathlib import Path

VARIANTS = {
    # name: (model, think, options overrides, layout flags)
    #   persona_last: persona lines moved from the system prompt to the user message (shared prefix)
    #   compact:      raw plan JSON dropped, route numbers rounded (min, km), router_tool not advertised
    #   terse:        asks for one or two sentences before the decide_trips call instead of a talk-through
    "ref":            ("qwen3.6:27b", True,  {"num_ctx": 12288, "num_gpu": 999, "num_predict": 3072}, set()),
    "nothink":        ("qwen3.6:27b", False, {"num_ctx": 12288, "num_gpu": 999, "num_predict": 512},  set()),
    "nothink_shared": ("qwen3.6:27b", False, {"num_ctx": 4096, "num_predict": 512},                   set()),
    "prefix_nothink": ("qwen3.6:27b", False, {"num_ctx": 12288, "num_gpu": 999, "num_predict": 512},  {"persona_last"}),
    "compact_shared": ("qwen3.6:27b", False, {"num_ctx": 4096, "num_predict": 512},                   {"compact"}),
    "terse_shared":   ("qwen3.6:27b", False, {"num_ctx": 4096, "num_predict": 256},                   {"compact", "terse"}),
    "brief_compact":  ("qwen3.6:27b", True,  {"num_ctx": 4096, "num_predict": 2048},                  {"compact"}),
    "9b_brief":       ("qwen3.5:9b",  True,  {"num_ctx": 8192, "num_gpu": 999, "num_predict": 3072},  set()),
    "9b_nothink":     ("qwen3.5:9b",  False, {"num_ctx": 8192, "num_gpu": 999, "num_predict": 512},   set()),
    "9b_terse":       ("qwen3.5:9b",  False, {"num_ctx": 8192, "num_gpu": 999, "num_predict": 256},   {"compact", "terse"}),
}
PERSONA_RE = re.compile(r"\n\nWho you are:\n(?:- .*\n)+")

def post(url, body, timeout=900):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        out = json.loads(r.read())
    return out, time.time() - t0

def decision_of(msg):
    for tc in msg.get("tool_calls") or []:
        f = tc.get("function", tc)
        if f.get("name") == "decide_trips":
            args = f.get("arguments") or {}
            d = args.get("decisions", args.get("value"))
            if isinstance(d, str):
                try: d = json.loads(d)
                except Exception: return None
            if not isinstance(d, list): return None
            try: return sorted({(int(x["trip"]), str(x["mode"])) for x in d})
            except Exception: return None
    return None

def stratum(sysm, user):
    car = "have a car" in sysm and "don't have a car" not in sysm
    if not car: return "no_car"
    return "owner_on_pt" if re.search(r"currently by pt", user) else "owner_other"

ROUTE_RE = re.compile(r'\{"mode":"(\w+)","travelTimeSeconds":([\d.]+),"distanceMeters":([\d.]+),"transfers":(\d+),"feasible":(?:true|false)(?:,"requires":"([^"]*)")?\}')

def _route(m):
    mode, t, d, tr, req = m.group(1), float(m.group(2)), float(m.group(3)), int(m.group(4)), m.group(5)
    out = f"{mode} {round(t / 60)} min, {d / 1000:.1f} km" + (f", {tr} transfers" if mode == "pt" else "")
    return out + (f" (requires {req})" if req else "") + ";"

def rewrite(body, variant):
    model, think, opts, layout = VARIANTS[variant]
    b = json.loads(json.dumps(body))
    b["model"], b["think"] = model, think
    b["options"] = {**{k: v for k, v in b["options"].items() if k not in ("num_gpu",)}, **opts}
    sysm, user = b["messages"][0]["content"], b["messages"][1]["content"]
    if "persona_last" in layout:
        m = PERSONA_RE.search(sysm)
        if m:
            sysm = sysm[:m.start()] + "\n\n" + sysm[m.end():]
            user = "Who you are:\n" + m.group(0).split("Who you are:\n", 1)[1] + "\n" + user
    if "compact" in layout:
        j, i = user.find("Here is today's plan"), user.find("What you already know")
        if 0 <= j < i: user = user[:j] + user[i:]
        user = ROUTE_RE.sub(_route, user).replace("travel time in seconds, distance in metres; ", "")
        b["tools"] = [t for t in b.get("tools", []) if t["function"]["name"] != "router_tool"]
    if "terse" in layout:
        k = sysm.find("Talk through it out loud.")
        if k >= 0:
            e = sysm.find("\n\n", k)
            sysm = sysm[:k] + "Say in one or two short sentences what matters to you today." + sysm[e:]
        user = user.replace(" Talk through your choices as you go.", "")
        user += "\n\nAnswer in at most two short sentences in your own voice, then call decide_trips."
    b["messages"][0]["content"], b["messages"][1]["content"] = sysm, user
    return b

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run_dir"); ap.add_argument("out_dir")
    ap.add_argument("--variants", default="ref,nothink")
    ap.add_argument("--per-stratum", type=int, default=10)
    ap.add_argument("--repeats", type=int, default=1, help="repeats of every variant (ref repeated measures sampling noise)")
    ap.add_argument("--url", default="http://localhost:11434")
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    run, out = Path(a.run_dir), Path(a.out_dir); out.mkdir(parents=True, exist_ok=True)
    stats = [l.split(",") for l in open(run / "llm_person_stats_combined.csv").read().splitlines()[1:]]
    seen, pool = set(), defaultdict(list)
    for (it, pid, *_), line in zip(stats, open(run / "llm_chat_log_ChatLog_combined.jsonl")):
        if pid in seen: continue
        seen.add(pid); e = json.loads(line); body = json.loads(e["requestBody"])
        msg = json.loads(e["responseBody"]).get("message", {})
        pool[stratum(body["messages"][0]["content"], body["messages"][1]["content"])].append(
            dict(pid=pid, it=int(it), body=body, logged=decision_of(msg)))
    rnd = random.Random(a.seed); sample = []
    for s in ("owner_on_pt", "owner_other", "no_car"):
        rnd.shuffle(pool[s]); sample += [dict(x, stratum=s) for x in pool[s][:a.per_stratum]]
    print(f"sample: {Counter(x['stratum'] for x in sample)}", flush=True)
    loaded = set()
    res_f = open(out / "results.jsonl", "a")
    try:
        for v in a.variants.split(","):
            for rep in range(a.repeats):
                for x in sample:
                    b = rewrite(x["body"], v)
                    try:
                        r, wall = post(a.url + "/api/chat", b)
                    except Exception as ex:
                        rec = dict(variant=v, rep=rep, pid=x["pid"], stratum=x["stratum"], error=str(ex)); print(rec, flush=True)
                        res_f.write(json.dumps(rec) + "\n"); res_f.flush(); continue
                    loaded.add((b["model"], b["options"].get("num_ctx")))
                    msg = r.get("message", {})
                    rec = dict(variant=v, rep=rep, pid=x["pid"], stratum=x["stratum"], wall=wall,
                               load=r.get("load_duration", 0) / 1e9, prompt_tok=r.get("prompt_eval_count", 0),
                               prompt_s=r.get("prompt_eval_duration", 0) / 1e9, gen_tok=r.get("eval_count", 0),
                               gen_s=r.get("eval_duration", 0) / 1e9, decision=decision_of(msg), logged=x["logged"],
                               thinking_chars=len(msg.get("thinking") or ""), content=(msg.get("content") or "")[:600])
                    res_f.write(json.dumps(rec) + "\n"); res_f.flush()
                    print(f"{v:15s} r{rep} {x['stratum']:11s} {wall:5.1f}s load {rec['load']:4.1f} ptok {rec['prompt_tok']:4d} gtok {rec['gen_tok']:4d} dec {rec['decision']} logged {x['logged']}", flush=True)
    finally:
        res_f.close()
        ps = json.loads(urllib.request.urlopen(a.url + "/api/ps", timeout=10).read())["models"]
        for m in ps:
            if (m["name"], m.get("context_length")) in loaded and not (m["name"] == "qwen3.6:27b" and m.get("context_length") == 4096):
                try: post(a.url + "/api/generate", {"model": m["name"], "keep_alive": 0}, timeout=60); print("unloaded", m["name"], m.get("context_length"))
                except Exception as ex: print("unload failed", m["name"], ex)
    summarise(out)

def summarise(out):
    recs = [json.loads(l) for l in open(out / "results.jsonl") if l.strip()]
    by = defaultdict(list)
    for r in recs: by[r["variant"]].append(r)
    ref = {(r["pid"], r["rep"]): r["decision"] for r in by.get("ref", []) if "decision" in r}
    ref0 = {pid: d for (pid, rep), d in ref.items() if rep == 0}
    summ = {}
    for v, rs in by.items():
        ok = [r for r in rs if "wall" in r]
        valid = [r for r in ok if r["decision"] is not None]
        keep = lambda d: d == []
        agree_logged = sum(r["decision"] == r["logged"] for r in valid)
        agree_ref = [r["decision"] == ref0.get(r["pid"]) for r in valid if r["pid"] in ref0 and not (v == "ref" and r["rep"] == 0)]
        agree_kc = [keep(r["decision"]) == keep(r["logged"]) for r in valid]
        summ[v] = dict(n=len(rs), errors=len(rs) - len(ok), valid_decisions=len(valid),
                       wall_median=statistics.median(r["wall"] for r in ok) if ok else None,
                       wall_median_no_reload=statistics.median([r["wall"] for r in ok if r["load"] < 1] or [float("nan")]),
                       reloads=sum(r["load"] >= 1 for r in ok), prompt_tok_median=statistics.median(r["prompt_tok"] for r in ok) if ok else None,
                       gen_tok_median=statistics.median(r["gen_tok"] for r in ok) if ok else None,
                       gen_tok_s=sum(r["gen_tok"] for r in ok) / max(1e-9, sum(r["gen_s"] for r in ok)),
                       agree_with_logged=f"{agree_logged}/{len(valid)}", keep_change_agree_with_logged=f"{sum(agree_kc)}/{len(agree_kc)}",
                       agree_with_ref_rerun=f"{sum(agree_ref)}/{len(agree_ref)}" if agree_ref else None,
                       changes=sum(not keep(r["decision"]) for r in valid))
    json.dump(summ, open(out / "summary.json", "w"), indent=1)
    print(json.dumps(summ, indent=1))

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--summarise": summarise(Path(sys.argv[2]))
    else: main()
