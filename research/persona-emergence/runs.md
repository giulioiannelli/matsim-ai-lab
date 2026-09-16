# Run registry

One row per simulation run, no exceptions. Output dirs are gitignored — this
table is the durable record. `git SHA` = repo HEAD at launch.

| date | output dir | model | agents | iters | seed | cap | flags | git SHA | outcome (1-line) |
|---|---|---|---|---|---|---|---|---|---|
| 2026-07-23 | `siouxfalls-qwen3.5-0.8b-T0.3-N4096-reasoning-persona` | qwen3.5:0.8b (mari) | 2 | 1 | default | 4 | persona | 5248827 | Wire test: plumbing perfect, 0/2 plans (model too small, expected) |
| 2026-07-23 | `siouxfalls-qwen3.6-27b-T0.3-N6144-reasoning-persona` | qwen3.6:27b (mari) | 2 | 1 | default | 4 | persona | 5248827 | 1/2 applied (20997_1, deliberate keep w/ validation); 15840_2 productive but capped; 0 protocol failures |
| 2026-09-16 | `siouxfalls-qwen3.6-27b-T0.3-N6144-reasoning-persona-cmp-s4711` | qwen3.6:27b (mari) | 2 | 1 | 4711 | 10 | persona, cmp | d0fad25 | 2/2 applied (6 + 4 rounds), 0 protocol failures; 11.5 + 7.1 min/agent — 22% model reload, 12% prompt re-eval, gen 12 tok/s (27B spills at ctx 12288) |
| 2026-09-16 | `siouxfalls-qwen3.5-9b-T0.3-N6144-reasoning-persona-cmp-s4711` | qwen3.5:9b (mari) | 2 | 1 | 4711 | 10 | persona, cmp | d0fad25+ | 1/2 applied (15840_2, 9 rounds); 20997_1 capped at 11 rounds (evaluate_plan loop); 8.3 + 6.2 min/agent — gen 65 tok/s but 55% of wall is model reload (embedder evicts chat model every round) |
| 2026-09-16 | `siouxfalls-qwen3.5-9b-T0.3-N6144-reasoning-persona-cmp-s4711` (rerun, overwrote previous) | qwen3.5:9b (mari), embedder local | 2 | 1 | 4711 | 10 | persona, cmp, `--embedding-host=localhost --embedding-port=11435` | d0fad25+ | 2/2 applied (1 + 5 rounds); 0.5 + 3.4 min/agent; gen 90 s of 233 s; remaining ~20 s/round "load" traced to another user's llama3.1:8b jobs on mari, not to our stack |
| 2026-09-16 | `siouxfalls-qwen3.6-27b-T0.3-N6144-reasoning-persona-cmp-s4711` (rerun, overwrote 27B smoke #1) | qwen3.6:27b (mari), `gpuLayers: 999`, embedder local | 2 | 1 | 4711 | 10 | persona, cmp, `--embedding-host=localhost --embedding-port=11435` | d0fad25+ | 2/2 applied (9 + 3 rounds); 4.8 + 1.8 min/agent, 397 s total (was 1119 s); load 19 s total, prompt cache working, gen 26 tok/s |
| 2026-09-16 | `siouxfalls-qwen3.6-27b-T0.3-N6144-reasoning-persona-cmp-s4721` | qwen3.6:27b (mari), gpuLayers, embedder local | 3 | 3 | 4721 | 10 | persona, cmp | fd21563 | 9/9 applied, 66 tool calls clean, persona 9/9, no loops. BUT: 2 commuters switched car→walk/pt and froze at the stop all day (stage-activity bug, fixed after), score "rose" −40→+5 as artefact; 3rd agent kept car and was stuck daily. Ground gridlocked: 75.8k stuck/84.1k agents at it.0 |
