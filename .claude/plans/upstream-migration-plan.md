# Upstream Migration Plan — adopting the collaborator's new `matsim_llm_plugins`

> **Local-first, committed.** Authoritative analysis + strategy for moving onto the
> collaborator's rewritten plugin infrastructure while preserving the work that
> produced results. Written 2026-06-12. Supersedes the older "sync against the
> fork, not upstream" guidance in the roadmap/diary — the collaborator has now
> moved far ahead on `upstream`, and we *want* to adopt it.

## TL;DR (the reframe)

- **There is no git "fast-forward" that carries our work onto the new infra.** Our
  work was never committed as plugin commits on top of our fork point `bebc622`;
  it was applied as **edits to a vendored (copied) source tree inside the parent
  repo** (`matsim-ai-lab/src/main/java`). So this is a **re-vendor / re-port**, not
  a merge or rebase.
- **The fork (`origin = giulioiannelli/matsim_llm_plugins`) has ZERO unique work.**
  Its `May2025` == `bebc622` (our exact fork point, never advanced); its `main` ==
  old upstream. Nothing to save on the fork → syncing it to upstream is lossless
  and trivial.
- **"Completely different" is overstated at the code level.** It is the *same
  architecture*, renamespaced under `org.matsim.contrib.llm.*`, plus build
  modernization and a `chatcommons` API unification. ~14–26 commits on top of our
  exact base `bebc622` (which is a clean ancestor of every upstream branch). Our
  additions map cleanly onto the new package layout.
- **Our delta is large but cleanly separable**: 57 files, +4942 / −613. Mostly
  *additive* (new tools, persona prompt, model profiles, runners) and concentrated
  in files upstream barely touched.

## Decisions locked (2026-06-12, with user)

- **Posture:** private **downstream fork**, kept current by merging upstream. Edit
  plugin internals freely. PR back **opportunistically** when a fix/tool is broadly
  useful — not a standing obligation.
- **Track `upstream/main`** (the integrated release line). Cherry-pick from
  `session/cleanup-run-class` only if a specific newer commit is needed. Confirmed
  via live `git ls-remote` 2026-06-12: upstream's true newest is **Apr 23, 2026**
  (4 branches, no tags); he hasn't pushed since the April rewrite.
- **Mechanism = local SNAPSHOT dependency (option "a", `pip install`-style):**
  - The fork (`matsim_llm_plugins/`) is the live plugin source on a long-lived
    **`lab`** branch off `upstream/main`. Internal edits + bug fixes live here.
  - The parent (`matsim-ai-lab`) **stops vendoring**; it depends on the fork's
    locally-installed `org.matsim.contrib:matsim-llm-plugins:0.1.0-SNAPSHOT`
    (`./mvnw install` in the fork after internal changes or after a pull).
  - **New tools and prompts live in the PARENT**, registered via the module's
    public `getToolManager()` — so the frequent design-loop needs **no** plugin
    rebuild. Only deep-internal edits or upstream pulls trigger a fork rebuild.
  - Pull upstream = `git fetch upstream && git merge upstream/main` on `lab`
    (real 3-way merge). PR back = cherry-pick a clean commit → topic branch → PR.
- **Guiding principle (2026-06-13): keep `lab` as close to upstream as possible.**
  Stick to his configurations/patterns; push our features into the parent; PR bug
  fixes upstream so they leave `lab`; express tunables as config/data (e.g.
  `num_ctx` via Modelfile) rather than code divergence. Fewer `lab` patches =
  cleaner future merges.

## Branch topology (upstream = auzpatwary37, fetched 2026-06-12)

All descend linearly from our base `bebc622` — no divergent history.

| Branch | Tip | Date | What it is |
|---|---|---|---|
| `upstream/session/cleanup-run-class` | `cc2c407` | 04-23 | **Newest tip.** = main + 1 fix ("avoid duplicate auth arg, align RouterTool tests"). Source of the big refactors: `namespace llm packages under org.matsim.contrib`, `unify chat submit API around external validators`, remove hardcoded creds. |
| `upstream/main` | `7084f5c` | 04-23 | **Release line** (merged May2025 #9). MIT license, MATSim-libs release metadata, geotools 34.3, JUnit5/surefire. Recommended migration target. |
| `upstream/May2025` | `b452abd` | 04-23 | Merged cleanup #8. Nearly identical to `main`. The branch we used to track. |
| `upstream/dev` | `19e74e2` | 04-21 | Older integration branch (javadoc/logging). Subset of cleanup. |

**Nature of the change:** package rename `*  →  org.matsim.contrib.llm.*`; `pom`
coords now `org.matsim.contrib:matsim-llm-plugins:0.1.0-SNAPSHOT` (a *consumable
contrib*); `DefaultChatManager` simplified by ~246 lines around external
validators; new classes `LLMControllerListener`, `LLMReplanningStrategyProvider`.
Same packages otherwise: `matsimBinding`, `prompts`, `tools/Implement`,
`chatcommons`, `chatrequest`, `chatresponse`, `matsimdtobjects`, `rag`, `run`.

## How the parent consumes the plugin TODAY

**By vendoring, not by dependency.** The parent `pom.xml` has **no** dependency on
the plugin; it copies the plugin source into `src/main/java/` and declares the
plugin's transitive deps itself (okhttp, gson, langchain4j, protobuf, picocli).
This is *why* our edits live in the parent tree and drifted from upstream — and
why we are in this situation. The new infra being a proper contrib is the
collaborator's fix for exactly this drift.

## What we did — graded inventory (the "what to save" answer)

Delta computed as parent `src/main/java` vs upstream base `bebc622`.

### KEEP — produced results (high confidence)
- **Persona prompt system** ⭐⭐ — `prompts/PersonaPromptBuilder.java` (166) +
  `prompts/IndividualPrompt.java` persona reframe (+226). Roadmap Phase 1.7;
  persona emergence demonstrated (first-person, derives mode constraints from
  attributes). Additive → easy to re-home.
- **Comparison toolset** ⭐ — `tools/Implement/comparison/` (CompareRoutesTool 270,
  EvaluatePlanTool 196, PlanMetrics, RouteMetrics) + `tools/ToolFilter.java` /
  `tools/StagedToolFilter.java` (flag gating). Phase 4, 47/47 tests, both fire on
  persona.
- **`AvailableModesTool` (289) + `ActivityChainSummaryTool` (221)** ⭐ — used in the
  successful traces (`activity_chain_summary → available_modes → compare_routes`).
- **Robustness fixes** (in `LLMReplanningStrategyModule` +285, `ExtractPlanTool`
  +69, `matsimdtobjects/ActivityDTO`):
  - **Activity-type whitelist removal** (blocker B fix) — real **bug fix**; made
    car/everyday personas apply decisions. → **strong upstream-PR candidate.**
  - **`extract_plan` self-reference guard** (iter-1 mobsim crash fix) — real
    **bug fix**. → **upstream-PR candidate.**
  - **Deterministic exactly-N, seed-shuffled agent selection** — research-relevant
    (selects distinct personas).
  - **`--max-tool-iterations` flag** (de-hardcoded 10) + prompt **commit nudge**.
- **Model-profile + num_ctx plumbing** ⭐ — `matsimBinding/profile/` (ModelProfile,
  ModelProfileApplier, ModelProfileLoader, 229) + `LLMConfigGroup` additions (+170).
  Made qwen3.5:9b stable (`ctx=8192, maxTok=4096`). **See divergence note.**
- **Runners (project-level, stay in parent regardless)** —
  `org/matsim/project/RunSiouxFallsLLMAgents.java` (220, the main entry point),
  `RunSiouxFalls`, `RunKelheim`, `RunMatsim`, `RunMatsimApplication`,
  `RunMatsimFromExamplesUtils`, and `org/matsim/gui/` (shade Main-Class).

### KEEP but verify it earns its place
- **`ValidateTimingTool` (294)** — built; roadmap marks "done" but not observed
  firing in the success traces. Port, then confirm it's actually invoked.

### DROP — dead code (already excluded from the build in parent `pom.xml`)
- `org/matsim/project/llm/**` — `CreatePlanTool` (240), `LLMReplanningListener`
  (306), `LLMReplanningModule` (23). Excluded; pre-vendor-pull experiments,
  superseded by `RunSiouxFallsLLMAgents`.
- `org/matsim/project/RunMatsimWithLLM.java` (92), `RunSiouxFallsWithLLM.java`
  (105), `TestLLMConnection.java` (62) — excluded, "outdated constructor signatures".

### DROP — legacy / superseded
- `gsonprocessor/**` (PlanGson etc.) — CLAUDE.md: legacy, superseded by
  `matsimdtobjects`. Parent-only, absent from new infra. Drop unless still imported.

## The overlap / collision zone (where re-port effort concentrates)

On our highest-value files **our changes dominate** (upstream barely touched them):

| File | Upstream Δ (bebc622→main) | Our Δ | Re-port effort |
|---|---|---|---|
| `LLMConfigGroup.java` | ~6 | **+170** | low (re-add our config keys onto their file) |
| `LLMReplanningStrategyModule.java` | ~36 | **+285** | medium (selection + guards onto their version) |
| `IndividualPrompt.java` | ~6 | **+226** | low (mostly additive) |
| `ExtractPlanTool.java` | ~20 | **+69** | low |
| `matsimdtobjects/ActivityDTO.java` | ~8 | ~38 | low |
| **`chatcommons/DefaultChatManager.java`** | **−246 (rewritten)** | (ours) | **HIGH — re-apply our multi-turn/iteration edits onto their new unified API** |

## Two genuine divergences (decisions, not mechanical ports)

1. **Backend.** New infra **kept** `LmStudioChatRequest`/`OpenAiChatRequest` +
   `BackendType.LM_STUDIO` (the LM-Studio-pointed-at-Ollama workaround). We
   **replaced** these with `OllamaNative*` / `OpenAiCompat*` +
   `BackendType.OPENAI_COMPAT`/`OLLAMA_NATIVE` and num_ctx plumbing. Ours is
   arguably better (native Ollama, num_ctx) but **diverges**. Decide: port our
   split onto the new infra (and ideally upstream it), or adopt theirs and re-plumb
   only num_ctx.
2. **`DefaultChatManager`** was rewritten upstream around external validators. Our
   iteration-loop edits must be re-applied onto the new shape, not pasted.

## Definitive re-port scope (post-inspection, 2026-06-13)

Inspecting his new code shows **he independently built most of what we built**
(`maxToolIterations`, `numberOfAIAgents`, `seed`, `systemMessage`, `temperature`,
`maxTokens` are all config fields now; reasoning capture exists; a `size()<=3`
plan guard was added). This collapses the re-port: **`lab` ends up carrying ~2
small, upstream-PR-worthy patches; everything else moves to the parent, becomes
config, or is redundant.**

**Lands on `lab` (minimal downstream patches → then PR upstream):**
- **ActivityDTO whitelist fix** — `isVerified` still rejects free-form types via
  hardcoded `isAllowedType` (lines 92–117, incl. a duplicate/NPE-prone second
  check). Re-apply our fix (accept any non-blank type). **PR to upstream**; once
  merged it leaves `lab`.
- **System-prompt seam** — replanning hardcodes `IndividualPrompt.chatGPTSystemPrompt`
  (`LLMReplanningStrategyModule:397`). Add a small `SystemPromptProvider` interface
  (default = current behavior, Guice-overridable) so the **parent** injects
  per-person persona prompts. Generic, upstream-PR-worthy.

**Moves to the PARENT (no `lab` patch):**
- **Persona prompt** — `PersonaPromptBuilder` in the parent, bound to the seam.
- **All our tools** — `compare_routes`/`evaluate_plan`, `available_modes`,
  `activity_chain_summary`, `validate_timing`, the `ToolFilter`s — registered via
  the module's public `getToolManager()`.
- **CLI flags → config setters** — `maxToolIterations`, `numberOfAIAgents`, `seed`,
  `temperature`, `maxTokens` already exist upstream; the parent runner sets them.
- **The runners** — `RunSiouxFallsLLMAgents` etc., already parent-only.

**Now redundant (drop our version):** config dupes (temp/maxTokens/seed/
maxToolIterations/numberOfAIAgents); reasoning capture (verify it catches Ollama's
`message.reasoning` in the smoke run, re-apply only any gap); backend split +
grammar parsers (dropped per LM_STUDIO); model-profile package (superseded by
upstream config + `num_ctx` Modelfile — replace with a tiny parent-side model→config
map if useful).

**Dropped (dead/legacy):** `org/matsim/project/llm/**`, the 3 excluded `Run*`,
`gsonprocessor/**`.

**`num_ctx`:** via Ollama Modelfile (verify endpoint passthrough first) — not code.

**Blocker A — SOLVED by user (commit `1337661`), carries into migration.** Approach:
a MATSim `"llm"` **subpopulation** that gets the LLM strategy at weight 1.0 with
default scoring mirrored to it (so `checkConsistency` passes). Pieces:
`LLMReplanningStrategyModule.{StrategyName="LLMPlanner", LLM_SUBPOPULATION="llm"}` +
`putSubpopulation(person, "llm")` (plugin-side); `mirrorDefaultScoringToSubpopulation`
+ strategy config + `BlockerASubpopConfigTest` (parent-side). Carry the parent-side
pieces as-is; do subpop assignment in the parent runner where possible; add a `lab`
constant only if his module truly needs it. Also recent: licence/car reconciliation
(`313c8b9`, `2e482e2`) — rides along with `PersonaPromptBuilder` + `available_modes`.

## Fast-forward execution plan (2026-06-13)

**End-state:** the parent works **only** in the new `org.matsim.contrib.llm.*`
framework, consuming it as a local SNAPSHOT dependency; our proven work is preserved
on the new namespace; redundancies are dropped in favour of his protocol; the fork
`lab` carries a tiny set of contribution-worthy patches.

**Tool-development model (resolves the iteration-speed vs integrate-upstream
tension):** develop/iterate tools in the **parent** (fast loop, no plugin rebuild);
**graduate** a tool into the contrib (`lab`) + PR upstream once it's proven and
generally useful. So this migration puts all our tools in the parent; graduation is
a later, optional step.

**Phase 0 — Freeze & branch (safety).** Blocker-A work already committed ✓.
- `git tag pre-migration-vendored` ; `git checkout -b migrate/contrib-llm`.
- Fork already on `lab`@`upstream/main`; `May2025`@`bebc622` preserved ✓.

**Phase 1 — Stand up the contrib (`lab`) + install locally.**
- On fork `lab`, two clean PR-ready commits: (1) ActivityDTO free-form types
  (drop hardcoded whitelist + dup check); (2) `SystemPromptProvider` seam (default =
  current behavior, Guice-overridable) for parent persona injection.
- Blocker-A: inspect his `LLMReplanningStrategyModule` for a strategy-name constant
  + subpop hook; add a `lab` constant only if needed (prefer parent-side assignment).
- `./mvnw -f matsim_llm_plugins/matsim_llm_plugins/pom.xml install -DskipTests`
  → publishes `org.matsim.contrib:matsim-llm-plugins:0.1.0-SNAPSHOT` to `~/.m2`.

**Phase 2 — Rewire the parent build onto the dependency.**
- Add the dependency to the parent pom; reconcile version pins (grpc 1.68↔1.77,
  geotools, jackson) against the enforcer; drop now-provided transitive deps.
- Remove dead-code compiler excludes; delete dead/legacy (`org/matsim/project/llm/**`,
  `RunMatsimWithLLM`, `RunSiouxFallsWithLLM`, `TestLLMConnection`, `gsonprocessor/**`).

**Phase 3 — Re-home our KEEP work onto the new namespace (parent).**
- DELETE vendored plugin packages now provided by the dependency: `chatcommons`,
  `chatrequest`, `chatresponse`, the plugin `matsimBinding`, `matsimdtobjects`, the
  plugin `prompts`, `rag`, `run`, the framework `tools`.
- KEEP + migrate to `org.matsim.contrib.llm` imports: runners (`org/matsim/project/*`)
  + `org/matsim/gui`; our tools (parent pkg, `implements
  org.matsim.contrib.llm.tools.ITool`, registered via `getToolManager()`);
  `PersonaPromptBuilder` (bound to the seam); blocker-A wiring +
  `BlockerASubpopConfigTest`.
- DROP redundancies → use his protocol: config dupes
  (temp/maxTokens/seed/maxToolIterations/numberOfAIAgents → his setters), backend
  split + grammar parsers (→ his LM_STUDIO), model-profile pkg (→ his config +
  num_ctx Modelfile), our reasoning-capture (→ his), our crash guard (→ his
  null-return path).

**Phase 4 — num_ctx + offline verification (gate before any sim).**
- Probe whether Ollama's OpenAI-compat endpoint honors `num_ctx`; else bake a
  Modelfile (`qwen3.5-lab`, `PARAMETER num_ctx 8192`) and point `modelName` at it.
- `./mvnw test` green (RunMatsimTest, 47 comparison tests, ActivityDTO verify,
  BlockerASubpopConfigTest) + 3 curl probes (offline-verify-first).

**Phase 5 — Gated smoke (no regression).**
- One small gated run (persona + comparison tools, fixed agents, multi-seed);
  compare success / persona-emergence / tool-use vs pre-migration baseline; honor
  early-stop (<60%); output dir per naming convention.

**Phase 6 — Land & document.**
- Merge `migrate/contrib-llm` → master; update CLAUDE.md (namespace, build =
  dependency, run cmds), roadmap (supersede "never pull upstream"), MEMORY.md;
  diary entry; open upstream PRs (whitelist, seam) opportunistically.

**Ongoing:** new tools/AI-infra in the parent (fast loop) → graduate proven ones into
the contrib + PR. Pull his updates: `git merge upstream/main` on `lab` → `./mvnw
install`. Private downstream, always mergeable.

## Recommended git strategy

### Part 1 — the plugin fork (do regardless; lossless, trivial)
The fork has no unique work, so just bring it current.
```bash
# in matsim_llm_plugins/
git fetch upstream --prune
git checkout main && git merge --ff-only upstream/main   # target = release line
# sync the GitHub fork (origin). NOTE: SSH push is currently broken
# (publickey denied) — fix the key, switch origin to https, or:
gh repo sync giulioiannelli/matsim_llm_plugins --source auzpatwary37/matsim_llm_plugins
```
Safety first: `git branch archive/fork-point-bebc622 bebc622` before moving.
Target = `upstream/main` (stable). `cleanup-run-class` is newer by one auth/test
fix but unmerged; adopt only if that fix matters.

### Part 2 — the parent repo (the real migration; pick a consumption model)
**Before anything**, snapshot the current state so nothing is lost:
```bash
# in matsim-ai-lab/
git tag pre-migration-vendored-bebc622
# extract our delta as reusable patches (per-file) into migration/ for re-apply
```

Then choose **how the parent consumes the new infra** (the pivotal decision):

- **Option A — Maven dependency (cleanest, matches the modularization).**
  `mvn install` the new contrib locally → add
  `org.matsim.contrib:matsim-llm-plugins` to the parent pom → delete the vendored
  plugin packages, keep only `org/matsim/project/**` + `org/matsim/gui`. New
  tools/prompts live in the parent and register via the module's public
  `getToolManager()` (no plugin-source edits needed for tools). **Friction:** the
  in-place edits to `LLMConfigGroup` (+170) and `LLMReplanningStrategyModule`
  (+285) can't be edited in a JAR → must become upstream PRs or local
  subclasses/overrides. Bug fixes (activity-type whitelist, self-ref guard) →
  upstream PRs.
- **Option B — Re-vendor + re-apply (keep editing source freely).** Replace
  vendored packages with the new namespaced source; re-apply our 57-file delta
  (new `package` decls + re-port the overlap files). Keeps the lab's
  "mutate-the-plugin" velocity, but **re-creates the exact drift problem** we're
  untangling now.
- **Option C — Hybrid (pragmatic).** Re-vendor now to get unblocked fast (Option
  B mechanics, dropping dead/legacy code), **and** open upstream PRs for the
  clearly-shared pieces (activity-type whitelist bug fix first; then comparison
  tools, Ollama-native backend). Shrink the carried delta over time toward Option
  A.

**Chosen (2026-06-12):** a blend — **edit internals freely on the fork's `lab`
branch (Option B's freedom) but consume via a locally-installed SNAPSHOT
dependency (Option A's mechanism), with new tools/prompts in the parent.** See
*Decisions locked* above. This keeps the design-loop fast (no plugin rebuild for
new tools/prompts) while giving real git ancestry for upstream pulls.

## Execution checklist (once the consumption model is chosen)
1. Snapshot/tag parent + fork (above).
2. Sync the fork to `upstream/main`; update `matsim_llm_plugins/` working copy.
3. `mvn install` the new contrib; confirm it builds on Java 21 / MATSim 2025.0.
4. (Option A) wire the parent pom dependency; (Option B/C) re-vendor namespaced source.
5. Re-home KEEP items by bucket: tools/prompts → parent or upstream PR; bug fixes →
   upstream PR; config/profile → upstream or local subclass; runners → stay in parent.
6. Reconcile the two divergences (backend; DefaultChatManager).
7. Drop dead/legacy code (don't carry it forward).
8. Offline-verify (unit tests + curl probes) per `feedback_offline_verify_first`
   BEFORE any simulation; then one gated multi-seed validation to confirm no
   regression vs the pre-migration baseline (`feedback_never_regress_baseline`).
9. Update CLAUDE.md (new package names, build/run), the roadmap, and a diary entry.

## Open decisions
- **Resolved 2026-06-12** (see *Decisions locked*): posture = private downstream
  fork; track `upstream/main`; mechanism = local SNAPSHOT dependency with new
  tools/prompts in the parent; upstream PRs = opportunistic.
- **Backend — RESOLVED 2026-06-13: adopt upstream's `LM_STUDIO`.** Drop our
  `OllamaNative`/`OpenAiCompat` split, the diverging `BackendType` enum, and the
  grammar parsers — stick to his config. Preserve the qwen3.5 stability fix
  (`num_ctx=8192`) **out of code**: bake `PARAMETER num_ctx 8192` into an Ollama
  Modelfile (derived model) so his unmodified request layer works. Probe first
  whether the OpenAI-compat endpoint already honors `num_ctx`.
- **Pom reconciliation** — align the fork's pins (grpc-bom 1.68.1, geotools 34.3)
  with the parent/MATSim enforcer (parent uses grpc 1.77) when wiring the
  dependency. One-time, part of the build step.
- **Docs to update on completion** — `CLAUDE.md` (new `org.matsim.contrib.llm.*`
  package names, build/run, "vendored" → "dependency"), the roadmap's "sync
  against the fork, never upstream" convention (now superseded), and MEMORY.md.
