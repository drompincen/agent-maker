# Architecture Decisions

<!-- Format:
## [Date] Decision Title
**Context:** Why this decision was needed
**Decision:** What was decided
**Consequences:** Trade-offs accepted
-->

## 2026-05-23 Factory-vs-car: agents live outside the factory
**Context:** agent-maker's purpose is to produce portable, deployable agent bundles. If those bundles lived inside the factory, the boundary between "tool" and "artifact" would blur, and agents would tend to accumulate factory-specific assumptions that break portability.
**Decision:** Agents are bundles at user-chosen paths, never inside `agent-maker/`. Every command takes a path argument (`am new agent <name> --at <path>`, `am avatar <path>`, `am tune <path>`, `am publish <path>`). Default for `--at` is `./<name>` in the user's CWD, not the factory.
**Consequences:** Two coordinate systems — the factory tracks `runs/` by agent path (hashed for uniqueness); agent location is the user's responsibility. The factory cannot assume an agent is on the same filesystem long-term.

## 2026-05-23 drom-flow is factory-only
**Context:** drom-flow is a "Claude companion for consistency" — it provides closed-loop, orchestrate, plans, skills, hooks. The temptation was to inherit it into every avatar so agents share the same consistency layer. The user explicitly rejected this ("drom-flow is part of the factory, not the car").
**Decision:** The factory uses drom-flow for its own operations. The avatar overlay (`am avatar`) is BARE — it contains only the agent's own bundle contents. No drom-flow files leak into `$CLAUDE_CONFIG_DIR`.
**Consequences:** Agents ship clean and run identically wherever they're deployed. The factory cannot count on drom-flow-shaped behaviors inside the avatar (hooks, statusline, etc.) — anything the agent needs must live in the bundle.

## 2026-05-23 `claude -p` is the agent runtime; no Java SDK
**Context:** There is no Java SDK for claude-agent-sdk. Options were: shell out to the Python SDK via subprocess, call the Anthropic Java SDK directly and re-implement the agent loop, or shell out to the `claude` binary in print mode.
**Decision:** Use `claude -p` exclusively in v0. The binary IS the agent loop; reusing it sidesteps SDK absence and gives the factory the same runtime as deployed avatars.
**Consequences:** Streaming, tool-call intercepts, and custom MCP transports aren't directly accessible — if v1+ needs them, we add a Python-SDK subprocess layer. For the triadic workflow (AUT → grade → tune), `-p` is sufficient.

## 2026-05-23 Grader independence — no AUT transcript access
**Context:** A grader that sees the AUT's reasoning is biased by it. The AUT might justify a bad answer plausibly and charm the grader into a high score, breaking the tuning signal.
**Decision:** Graders receive ONLY the task's `instructions`, the `expected/` directory, and the AUT's `output/` directory. They do NOT see `transcript.jsonl`. This is enforced at the `am run` orchestration layer.
**Consequences:** Graders cannot do trace-based debugging of why an output was produced. That's the right trade — the grader's job is to judge the output, not the process.

## 2026-05-23 Tuner sandboxed via git worktree + advisory permissions
**Context:** The tuner is an LLM-driven editor with destructive potential. Permissions in `settings.json` are advisory at the tool layer — a determined tuner could still attempt out-of-scope writes.
**Decision:** Two-layer enforcement. (a) The tuner avatar's `settings.json` denies writes outside `allow_edit` scopes. (b) The tuner runs inside a `git worktree` rooted at the agent's path, so even bypassed writes can't escape the agent subtree. Tuner output is a unified diff verified with `git apply --check` before being applied back to the agent's home path.
**Consequences:** Tuning requires the agent to be inside a git repo (`git init` in `--at` path if absent). The worktree adds disk + setup overhead per tune run but is cleaned up after.

## 2026-05-23 Default model is opus for all three roles
**Context:** The user specified opus across AUT, Grader, and Tuner. Tradeoff: cost. Three opus sessions per iter × N iters can grow fast.
**Decision:** Default `model: opus` in `agent.yaml`. Per-agent override always available. `am tune --budget-usd <cap>` enforces a hard cost stop.
**Consequences:** Quality-first default. Users who need cheaper tuning swap to Sonnet/Haiku in `agent.yaml` per role. Per-iter cost is logged in plan notes for visibility.

## 2026-05-23 Java 17 + jbang single-file scripts for the CLI
**Context:** Need a CLI surface that's frictionless to install (no Maven/Gradle ceremony), single-file per command for fast iteration, and runs anywhere Java does.
**Decision:** Java 17 (jbang defaults; widely available); jbang `.java` files with `//DEPS` magic comments; picocli for CLI parsing. `jbang-catalog.json` exposes `am` as an installable alias.
**Consequences:** No build system — fast iteration, easy distribution. If the CLI outgrows single-file files later, promote to a regular module. Users need jbang installed.
