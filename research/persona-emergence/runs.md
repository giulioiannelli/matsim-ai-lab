# Run registry

One row per simulation run, no exceptions. Output dirs are gitignored — this
table is the durable record. `git SHA` = repo HEAD at launch.

| date | output dir | model | agents | iters | seed | cap | flags | git SHA | outcome (1-line) |
|---|---|---|---|---|---|---|---|---|---|
| 2026-07-23 | `siouxfalls-qwen3.5-0.8b-T0.3-N4096-reasoning-persona` | qwen3.5:0.8b (mari) | 2 | 1 | default | 4 | persona | 5248827 | Wire test: plumbing perfect, 0/2 plans (model too small, expected) |
| 2026-07-23 | `siouxfalls-qwen3.6-27b-T0.3-N6144-reasoning-persona` | qwen3.6:27b (mari) | 2 | 1 | default | 4 | persona | 5248827 | 1/2 applied (20997_1, deliberate keep w/ validation); 15840_2 productive but capped; 0 protocol failures |
