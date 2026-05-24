---
title: Build agent-maker on drom-flow
status: completed
created: 2026-05-23
updated: 2026-05-23
current_chapter: 6
---

# Plan: Build agent-maker on drom-flow

A jbang CLI (`am`) that operates a **factory** for producing portable claude-code agents. The factory does NOT contain the agents it produces — agents are bundles at user-chosen paths that ship anywhere, with no dependency on the factory or on drom-flow. The factory contains only: templates (blueprints), graders + tuners (equipment), test samples, run records, and the CLI.

**Factory vs. car** — agent-maker is the factory; agents are the cars. Cars roll off the line and drive away. The factory keeps the assembly tools; it does not garage finished cars.

The triadic workflow: **Agent-Under-Test (AUT)** runs a task on input artifacts → **Grader** scores the output → **Tuner** edits the AUT's source files (prompts, skills, Python scripts) → loop until target score, budget, max-iters, or plateau.

drom-flow is the factory's consistency layer: `workflows/closed-loop.md`, `scripts/orchestrate.sh` (regression detection, per-iter JSON reports), `drom-plans/`, 27 skills, lifecycle hooks. agent-maker rides on top — it does **not** reinvent these. Agents produced by the factory do NOT inherit drom-flow — they ship bare.

## Repo layout (the factory)

```
agent-maker/                           # FACTORY — drom-flow installed
├── CLAUDE.md, .claude/, context/, drom-plans/, workflows/, scripts/orchestrate.sh   (drom-flow)
├── cli/Am.java + cli/cmd/*.java       # jbang CLI sources
├── templates/{agent,task,grader,tuner}/   # blueprints
├── graders/                            # built-in graders (agent-shaped)
├── tuners/                             # built-in tuners (agent-shaped)
├── samples/                            # example agents/tasks for testing the factory
├── runs/<task>/<agent>/<run>/iter-NN/  # production records
└── scripts/tune-<agent>-<task>.sh      # generated per-tune orchestration scripts
```

## What an agent bundle looks like (outside the factory)

```
<wherever-the-user-wants>/<agent>/
├── agent.yaml                  # id, model (default opus), allow_edit, tool_allowlist, mcp
├── system-prompt.md
├── skills/<skill>/<skill>.md   # claude-code skill format
├── scripts/                    # python + shell scripts the agent invokes
├── hooks/                      # optional agent-specific hooks
├── mcp/servers.json
└── memory/MEMORY.md            # seed memory
```

No drom-flow files. No factory references. Self-contained, ships anywhere.

## Chapter 1: Foundations
**Status:** completed
**Depends on:** none

- [x] Create factory dirs — `cli/`, `templates/{agent,task,grader,tuner}/`, `graders/`, `tuners/`, `samples/`, `runs/`, `docs/schemas/` (with .gitkeep) — [agent-maker/]
- [x] Write `README.md` — factory-vs-car framing, triadic loop, drom-flow's factory-only role, how to use `am` — [README.md]
- [x] Record core design decisions (7 entries) — factory-vs-car; drom-flow factory-only; `claude -p` runtime; grader independence; tuner sandboxed; opus default; Java 17 + jbang — [context/DECISIONS.md]
- [x] Define `agent.yaml` schema — id, model (default opus), persona, allow_edit, tool_allowlist, mcp_servers, hooks, memory_seed — [docs/schemas/agent.yaml.md]
- [x] Define grader output contract — JSON `{score, pass, issues[{severity,area,msg,suggestion?}], rationale}` with validation rules — [docs/schemas/grade.json.md]
- [x] Define `task.yaml` schema — id/desc/instructions, artifacts_dir, expected_dir, grader, target_score, splits (train/dev/holdout), timeout — [docs/schemas/task.yaml.md]
- [x] Set up `jbang-catalog.json` — alias `am` → `cli/Am.java` for `jbang app install am@agent-maker` — [jbang-catalog.json]

**Notes:**
> Done 2026-05-23 in one parallel batch. Skeleton dirs in place, README sets the factory-vs-car framing, all three schemas (`agent.yaml`, `task.yaml`, grade contract) documented, jbang catalog exposes `am`. Ready for Chapter 2.

## Chapter 2: `am new` — Scaffolding
**Status:** completed
**Depends on:** Chapter 1

- [x] CLI dispatcher with picocli — `am <subcommand>` — [cli/Am.java]
- [x] `am new agent <name> [--at <path>]` — scaffolds agent bundle at `<path>` (default `./<name>` in CWD, **NOT inside the factory**); copies from `templates/agent/` with `{{NAME}}` substitution — [cli/cmd/New.java]
- [x] `am new task <name> [--at <path>]` — defaults `./<name>` in CWD — [cli/cmd/New.java]
- [x] `am new grader|tuner <name>` — defaults to factory's `graders/<name>` or `tuners/<name>` (factory equipment); `--at` overrides — [cli/cmd/New.java]
- [x] Write agent template — `agent.yaml` (model: opus), `system-prompt.md`, empty `memory/MEMORY.md`, .gitkeep for skills/scripts/hooks/mcp — [templates/agent/]
- [x] Write grader template — `agent.yaml` + system prompt baking the grade JSON contract — [templates/grader/]
- [x] Write task template — `task.yaml`, `artifacts/.gitkeep`, `expected/.gitkeep`, `splits.yaml` — [templates/task/]
- [x] Write tuner template — `agent.yaml` with read+edit permission, system prompt covering minimal-diff philosophy + unified-diff output contract — [templates/tuner/]
- [x] Generate working samples — `samples/echo-bot/`, `samples/tasks/say-hi/`, built-in `graders/contains-hello/` — [samples/, graders/contains-hello/]
- [x] Smoke test — `jbang cli/Am.java new agent foo --at ./.smoke/foo` produced expected tree; `am --help` shows all 6 subcommands — [README.md]

**Notes:**
> Done 2026-05-23. CLI compiles and runs via jbang. Stubs for avatar/run/tune/factory/publish print "[Chapter N] not yet implemented". `{{NAME}}` substitution verified. `AGENT_MAKER_HOME` env var allows running from outside the factory dir; otherwise the CLI walks up looking for `jbang-catalog.json`. Smoke artifact directory is gitignored. Ready for Chapter 3 (avatar overlay).

## Chapter 3: `am avatar` — Impersonation overlay (bare)
**Status:** completed
**Depends on:** Chapter 2

- [x] Avatar launcher — `am avatar <agent-path>` builds ephemeral `$CLAUDE_CONFIG_DIR`, copies `skills/` + `hooks/`, writes synthesized `settings.json` from `agent.yaml`, seeds `CLAUDE.md` from `memory/`, execs `claude --append-system-prompt @system-prompt.md --model …` — [cli/cmd/Avatar.java]
- [x] **Bare overlay** — overlay contains ONLY the agent's bundle contents. No drom-flow files leak in — [cli/cmd/Avatar.java]
- [x] Env hygiene — explicitly sets `CLAUDE_CONFIG_DIR` and removes `CLAUDE_HOME` from inherited env before exec — [cli/cmd/Avatar.java]
- [x] Settings synthesis — translates `agent.yaml` tool_allowlist → `permissions`, mcp_servers → `mcpServers`, hooks → `hooks` — [cli/cmd/Avatar.java]
- [x] Headless mode — `-p/--print` passthrough; required by Chapter 4 — [cli/cmd/Avatar.java]
- [x] Cleanup — default deletes ephemeral dir on exit; `--keep-config` preserves and prints path — [cli/cmd/Avatar.java]
- [x] Shared helpers — `Bundle.readAgentYaml`, `Bundle.resolveModelId` (opus→claude-opus-4-7 etc.) — [cli/util/Bundle.java]
- [x] Smoke test — `jbang cli/Am.java avatar samples/echo-bot --dry-run` synthesizes correct command with `--append-system-prompt`, `--model claude-opus-4-7`, and ephemeral `$CLAUDE_CONFIG_DIR` — [README.md]

**Notes:**
> Done 2026-05-23. snakeyaml + jackson-databind added to //DEPS. Full claude exec requires `claude` on PATH; tested via `--dry-run` which prints the resolved command. Avatar overlay is bare per design — agent ships clean.

## Chapter 4: `am run` — Headless task + grade
**Status:** completed
**Depends on:** Chapter 3

- [x] `am run <agent-path> --task <task-path>` — stages AUT in `aut-work/` (with `artifacts/` + `output/`), spawns AUT via AvatarLauncher (`-p` mode with `--output-format stream-json`), captures transcript, then stages + runs grader — [cli/cmd/Run.java]
- [x] Output capture — `transcript.jsonl` + `aut.stderr.log` for AUT; `grader-transcript.jsonl` + `grader.stderr.log` for grader; `output/` snapshot; `input.json` of the invocation — under `runs/<task>/<agent-key>/<run-id>/iter-1/` — [cli/cmd/Run.java]
- [x] Grader invocation — grader receives `instructions.md`, `expected/`, and `aut-output/` ONLY (never the AUT's transcript) — [cli/cmd/Run.java]
- [x] Validate `grade.json` against the contract — score number in `[0,1]`, pass boolean, issues list, rationale required — [cli/cmd/Run.java]
- [x] Run-key collision handling — SHA-256(absolute agent path) prefix appended to agent name → `<agent-name>-<8hex>` — [cli/cmd/Run.java]
- [x] Refactor — AvatarLauncher extracted to util/ so Avatar and Run share launch logic — [cli/util/AvatarLauncher.java]
- [x] Smoke test — `jbang cli/Am.java run samples/echo-bot --task samples/tasks/say-hi --dry-run` synthesizes both AUT and grader commands; creates correct `runs/say-hi/echo-bot-<hash>/<stamp>/iter-1/` layout — [README.md]

**Notes:**
> Done 2026-05-23. Run dir contents are gitignored (`runs/*` with `!runs/.gitkeep`). Full claude exec still requires the binary on PATH; `--dry-run` proves the wiring. Grader independence is enforced at the staging layer — the grader's working dir never contains `transcript.jsonl`.

## Chapter 5: `am tune` — Closed-loop with tuner
**Status:** completed
**Depends on:** Chapter 4

- [x] `am tune <agent-path>` — `--task --grader --tuner --max-iters --target-score --allow-edit --plateau-epsilon --dry-run --keep-config` — [cli/cmd/Tune.java]
- [x] Loop driver implemented in Java (instead of shell-script generation) — calls TaskRunner per iter, checks convergence, spawns tuner avatar, applies edits — [cli/cmd/Tune.java]
- [x] Tuner sandboxing — runs inside `iter-NN/tuner-work/` with `aut-source/` (copy of bundle); tuner avatar's permission denies enforced via `settings.json`; runner applies only scope-matching changes back — [cli/cmd/Tune.java]
- [x] Direct-edit model (instead of unified-diff) — tuner edits files in `aut-source/` directly; runner walks tunedSrc, compares byte-for-byte to home, copies changed files within allowed scopes — [cli/cmd/Tune.java]
- [x] Convergence detection — target_score reached, grader-set `pass: true`, score-variance plateau over last 3 iters, tuner made zero in-scope changes, max-iters — [cli/cmd/Tune.java]
- [x] Per-run SUMMARY.md with iter→score table + exit reason — [cli/cmd/Tune.java]
- [x] Default factory tuner bundle — `tuners/default/` with agent.yaml + system prompt enforcing direct-edit + scope-respect contract — [tuners/default/]
- [x] Smoke test — `jbang cli/Am.java tune samples/echo-bot --task samples/tasks/say-hi --max-iters 1 --dry-run` wires AUT → grader → tuner correctly — [README.md]

**Notes:**
> Done 2026-05-23. v0 simplifications vs original plan: (a) loop is pure Java, not generated orchestrate.sh; (b) tuner edits files directly instead of emitting a unified diff (less verifiable but simpler); (c) no git worktree (scope-checked copy is the sandbox); (d) no budget-usd tracking yet (token usage requires parsing stream-json); (e) no holdout eval split yet. All are TODO upgrades; the core triadic loop works end-to-end in dry-run. TaskRunner util extracted so Run and Tune share the AUT+grade pipeline.

## Chapter 6: `am factory` + `am publish`
**Status:** completed
**Depends on:** Chapter 5

- [x] `am factory <factory.yaml>` — parses spec, ExecutorService with `--parallel` workers, shells out to `jbang cli/Am.java tune ...` per entry, aggregates per-job exit codes — [cli/cmd/Factory.java]
- [x] Factory schema — `agents: [{agent, task, grader?, tuner?, max_iters?, target_score?, allow_edit?}]`; sample at `factories/example.yaml` — [factories/example.yaml]
- [x] Aggregate report — per-job idx/agent/task/exit/log, written to `factories/<spec-stem>-<stamp>-report.md` (gitignored) — [cli/cmd/Factory.java]
- [x] `am publish <agent-path> [--to <dest>]` — zips the bundle; default dest `<factory>/dist/<id>-<version>.zip` — [cli/cmd/Publish.java]
- [x] Smoke test — `jbang cli/Am.java factory factories/example.yaml --dry-run` → "ALL OK 1/1"; `jbang cli/Am.java publish samples/echo-bot --to ./.smoke/echo-bot.zip` → 3-file zip (583 bytes) verified via unzip -l — [README.md]

**Notes:**
> Done 2026-05-23. Plan complete. Factory parallelism via shell-out to jbang per agent (simple; each child resolves deps independently — could optimize by sharing a built jar later). Factory reports gitignored. Publish includes every regular file in the bundle (no excludes — agent owners control what ships by what they put in the bundle).

---

## Agent Spawn Plan

Chapters sequential; within each, parallelize aggressively:

- **Chapter 1** — 7 independent docs/schema items → spawn 7 agents in ONE message (run_in_background: true)
- **Chapter 2** — `Am.java` + `New.java` first (sequential); then 4 templates in parallel; then sample bundles in parallel; smoke test last
- **Chapter 3** — `Avatar.java` skeleton first; then in parallel: env hygiene, settings synthesis, `-p` passthrough, cleanup; smoke test last
- **Chapter 4** — `Run.java` skeleton first; then output capture and grader invocation in parallel; validation last; smoke test
- **Chapter 5** — `Tune.java` skeleton first; then in parallel: orchestrate template, plan template, tuner sandbox, diff contract, convergence logic, holdout eval; smoke test last
- **Chapter 6** — `Factory.java` and `Publish.java` in parallel; schema docs and report formatter in parallel; smoke test last

## Risks

- **No Java SDK for claude-agent-sdk.** Mitigation: shell out to `claude -p` exclusively in v0. The binary IS the agent loop; no SDK needed for the triadic workflow.
- **Avatar bare-ness leak.** Host shell `CLAUDE_CONFIG_DIR` / claude env vars could bleed into avatars. Mitigation: explicit env hygiene at exec; document required env discipline.
- **Tuner sandbox enforcement.** Permissions in settings.json are advisory at the tool layer. Mitigation: run tuner inside a `git worktree` rooted at the agent path; apply diffs back via `git apply --check` then `git apply`.
- **Overfitting.** Tuner driving target_score on one task may regress others. Mitigation: holdout split per iter; multi-task tuning rotates tasks or picks worst-scoring.
- **Cost runaway with opus-everywhere.** All three roles on opus = 3× opus per iter × N iters. Mitigation: `--budget-usd` hard stop; per-iter cost logged in plan notes; per-agent model override in `agent.yaml`.
- **Agent path collisions.** Two agents same name, different paths → `runs/` keys collide. Mitigation: hash absolute path into the run key.

## Open Questions

All resolved by user 2026-05-23:
- Model — **opus** for all three roles (AUT, grader, tuner) by default
- Java — **17**
- Agent location — **external** to the factory (path arg; default `./<name>` in CWD)
- Tuner default edit scope — **prompt, skills, scripts** (Python scripts included)
- drom-flow inheritance — **factory only**; agents ship bare
- JavaDucker — **out of scope**
