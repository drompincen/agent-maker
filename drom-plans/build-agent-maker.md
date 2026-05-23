---
title: Build agent-maker on drom-flow
status: in-progress
created: 2026-05-23
updated: 2026-05-23
current_chapter: 2
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
**Status:** pending
**Depends on:** Chapter 1

- [ ] CLI dispatcher with picocli — `am <subcommand>` — [cli/Am.java]
- [ ] `am new agent <name> [--at <path>]` — scaffolds agent bundle at `<path>` (default `./<name>` in CWD, **NOT inside the factory**); copies from `templates/agent/` with name substitution — [cli/cmd/New.java]
- [ ] `am new task <name> [--at <path>]` — defaults `./<name>` in CWD (tasks are typically user-owned) — [cli/cmd/New.java]
- [ ] `am new grader|tuner <name>` — defaults to factory's `graders/<name>` or `tuners/<name>` (factory equipment); `--at` to override — [cli/cmd/New.java]
- [ ] Write agent template — minimal `agent.yaml` (`model: opus`), `system-prompt.md`, one stub skill, `scripts/.gitkeep`, empty `memory/MEMORY.md` — [templates/agent/]
- [ ] Write grader template — `agent.yaml` + system prompt baking the grade JSON contract — [templates/grader/]
- [ ] Write task template — `task.yaml`, `artifacts/.gitkeep`, `expected/.gitkeep`, `splits.yaml` — [templates/task/]
- [ ] Write tuner template — `agent.yaml` with read+edit-only-target permission, system prompt covering minimal-diff philosophy and `prompt,skills,scripts` default edit scope — [templates/tuner/]
- [ ] Generate working samples — `samples/echo-bot/`, `samples/tasks/say-hi/`, and built-in `graders/contains-hello/` — to prove templates end-to-end — [samples/, graders/contains-hello/]
- [ ] Smoke test — `am new agent foo --at /tmp/foo` produces expected tree outside the factory — [README.md]

**Notes:**
> Agents land OUTSIDE the factory by default. Graders/tuners land INSIDE (factory equipment).

## Chapter 3: `am avatar` — Impersonation overlay (bare)
**Status:** pending
**Depends on:** Chapter 2

- [ ] Avatar launcher — `am avatar <agent-path>` builds ephemeral `$CLAUDE_CONFIG_DIR`, symlinks bundle's `skills/`, copies `hooks/`, writes synthesized `settings.json` from `agent.yaml`, seeds `memory/`, execs `claude --append-system-prompt @<agent-path>/system-prompt.md` — [cli/cmd/Avatar.java]
- [ ] **Bare overlay** — overlay contains ONLY the agent's bundle contents. No drom-flow files leak in. The avatar runs exactly as the agent will run when deployed to a clean machine — [cli/cmd/Avatar.java]
- [ ] Env hygiene — explicitly unset/override host `CLAUDE_CONFIG_DIR` and related env vars before exec, so the host's claude-code config doesn't leak into the avatar — [cli/cmd/Avatar.java]
- [ ] Settings synthesis — translate `agent.yaml`'s MCP / hooks / permissions into a valid `settings.json` — [cli/cmd/Avatar.java]
- [ ] Headless mode — `--print/-p` passthrough; required for Chapter 4 — [cli/cmd/Avatar.java]
- [ ] Cleanup — `--cleanup` nukes ephemeral dir on exit; otherwise persist under `runs/.avatars/<run-id>/` for debugging — [cli/cmd/Avatar.java]
- [ ] Smoke test — `am avatar samples/echo-bot` opens interactive session in persona; `-p "say hi"` returns text non-interactively — [README.md]

**Notes:**
> "Bare" matters: if the avatar works here, it works when shipped to a clean machine. Any drom-flow leakage into the avatar would be a lie.

## Chapter 4: `am run` — Headless task + grade
**Status:** pending
**Depends on:** Chapter 3

- [ ] `am run <agent-path> --task <task-path>` — spawns AUT avatar headless on the task's instructions+artifacts, captures transcript+output, then spawns grader avatar to score — [cli/cmd/Run.java]
- [ ] Output capture — pipe `claude -p` JSON output; persist `transcript.jsonl`, `output/`, `grade.json` under `runs/<task-name>/<agent-name>/<run-id>/iter-1/` — [cli/cmd/Run.java]
- [ ] Grader invocation — feed grader ONLY the task + expected + AUT's output (**NOT** the AUT's transcript). Grader is itself an avatar — `am avatar <grader-path> -p ...` — [cli/cmd/Run.java]
- [ ] Validate `grade.json` against the contract from Chapter 1; fail loudly on contract violation — [cli/cmd/Run.java]
- [ ] Run-key collision handling — hash the absolute agent path into the run key so two agents with same name in different paths don't collide — [cli/cmd/Run.java]
- [ ] Smoke test — `am run samples/echo-bot --task samples/tasks/say-hi` produces a `runs/` directory with all three artifacts and a sensible score — [README.md]

**Notes:**
> Grader independence is load-bearing. Never let the grader see the AUT's reasoning.

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
