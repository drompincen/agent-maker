---
title: Build agent-maker on drom-flow
status: in-progress
created: 2026-05-23
updated: 2026-05-23
current_chapter: 5
updated: 2026-05-23
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
**Status:** pending
**Depends on:** Chapter 4

- [ ] `am tune <agent-path>` — full signature: `--task <task-path> --grader <grader-path> --tuner <tuner-path> --max-iters N --target-score 0.9 --budget-usd 5 --allow-edit prompt,skills,scripts --holdout <split>` — [cli/cmd/Tune.java]
- [ ] Generate per-run orchestration script — wraps `scripts/orchestrate.sh`; check_cmd = `am run` + grade; fix step = spawn tuner avatar with grade+transcript as input — [scripts/tune-<agent>-<task>.sh, templates/tune.sh.tmpl]
- [ ] Generate per-run drom-plan — one chapter per iteration with steps "run AUT", "grade", "tuner edit", "re-check"; free resume + statusline via drom-flow — [drom-plans/tune-<agent>-<task>-<run>.md]
- [ ] Tuner sandboxing — at tuner avatar launch, settings.json permissions block all writes except to the agent's path; additionally run the tuner inside a `git worktree` of the agent's home — [cli/cmd/Tune.java]
- [ ] Tuner output contract — unified diff + one-line rationale; verify with `git apply --check` before accepting; apply back to the agent's home path — [cli/cmd/Tune.java]
- [ ] Convergence detection — pass at target_score; plateau (score variance across last 3 iters < ε); budget exhaustion; max iters — [cli/cmd/Tune.java]
- [ ] Holdout eval each iter — score on holdout split reported but NOT visible to the tuner — [cli/cmd/Tune.java]
- [ ] Iteration history appended to factory's `context/MEMORY.md` per drom-flow protocol — [scripts/tune-<agent>-<task>.sh]
- [ ] Smoke test — `am tune samples/echo-bot --task samples/tasks/say-hi --max-iters 3` runs and converges or stops cleanly with proper artifacts — [README.md]

**Notes:**
> Default `--allow-edit prompt,skills,scripts` (Python scripts included). Wider scope (`mcp`, `hooks`, `all`) requires explicit flag.

## Chapter 6: `am factory` + `am publish`
**Status:** pending
**Depends on:** Chapter 5

- [ ] `am factory <factory.yaml>` — spawns N `am tune` runs in parallel; aggregates results — [cli/cmd/Factory.java]
- [ ] Factory schema — list of `{agent_path, task_path, grader_path, tuner_path, target_score, max_iters, allow_edit}` — [docs/schemas/factory.yaml.md]
- [ ] Aggregate report — per-agent final score, iterations used, diff summary, agent home path, total cost — [factories/<name>-report.md]
- [ ] `am publish <agent-path> [--to <dest>]` — packages agent bundle as a zip; ships to `<dest>` (default `dist/<name>-<version>.zip` inside factory) or a user-supplied path/URL — [cli/cmd/Publish.java]
- [ ] Smoke test — `am factory factories/example.yaml` with 2 trivial agents runs to completion and produces a report — [README.md]

**Notes:**
> After this chapter, the factory ships its core value: a reproducible pipeline that takes raw agents anywhere and produces tuned, exportable bundles anywhere.

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
