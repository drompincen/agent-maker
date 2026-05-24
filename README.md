# agent-maker

A factory for building and continuously improving portable [claude-code](https://docs.claude.com/claude-code) agents.

You define an agent (a directory of prompts, skills, and scripts), pair it with a task that has expected outputs, and run a closed loop: one agent does the work, a second agent grades it, a third agent rewrites the first agent's source files based on the grade — repeating until quality converges. The output is a versioned, portable agent bundle that ships anywhere.

## The metaphor

**agent-maker is the factory. Agents are the cars.** Cars roll off the assembly line and drive away. The factory keeps the assembly tools; it does not garage finished cars.

Agents produced by this factory are **portable bundles** — self-contained directories of `agent.yaml`, `system-prompt.md`, `skills/`, `scripts/`, `hooks/`, `mcp/`, and `memory/`. They live at user-chosen paths, run on any machine with claude-code, and have **no dependency** on this factory or its internal tooling.

## The problem this solves

Building a Claude agent is easy. Building a *reliable* Claude agent is hard — the system prompt is a moving target, the skills are interdependent, and there's no built-in feedback loop that improves the agent on its own. Most teams iterate by hand: tweak the prompt, run it on a few examples, eyeball the output, repeat. That's slow, inconsistent, and doesn't scale to many agents.

agent-maker treats agent construction like factory work: separate the **artifact** (the agent bundle), the **specification** (the task and grader), and the **process** (the tuning loop). The factory iterates automatically. You can produce one tuned agent or many — each evaluated against a held-out test set so you know if it actually got better.

## The triadic loop

```
   ┌────────────────────────────────────────────────────┐
   │                                                    │
   ▼                                                    │
┌──────────┐   output   ┌──────────┐  feedback          │
│   AUT    │───────────▶│  Grader  │──────────┐         │
│ (avatar) │            │ (avatar) │          │         │
└──────────┘            └──────────┘          ▼         │
   ▲                                    ┌──────────┐    │
   │   task + artifacts                 │  Tuner   │    │
   │                                    │ (avatar) │────┘
   │                                    └──────────┘   edits
   │                                                   agent's
   │                                                   prompt /
   │                                                   skills /
   │                                                   scripts
```

- **AUT** (Agent-Under-Test) — runs the task on input artifacts; produces output.
- **Grader** — scores the output against expected criteria. Sees ONLY the task + expected + AUT's output. Never sees the AUT's transcript.
- **Tuner** — reads the AUT transcript + grader feedback; edits the AUT's source files (sandboxed in a git worktree); the loop repeats.

Tuning continues until target_score, max_iters, budget cap, or score plateau.

## How tuning actually works

One iteration of `am tune <agent> --task <task>`:

1. **Stage** — the AUT, grader, and tuner are spawned as three separate avatars (claude-code processes), each with its own bundle, system prompt, and tool permissions. The AUT gets the task's `instructions` plus read-only access to `artifacts/`.
2. **Run** — the AUT works the task, writing to `output/`. Its full transcript is captured to `runs/.../iter-NN/transcript.jsonl`.
3. **Grade** — the grader avatar is given the task, `expected/`, and the AUT's `output/` — but NOT the transcript. It scores the output as JSON: `{score, pass, issues[], rationale}`.
4. **Decide** — if `score >= target_score`, exit. If the budget cap or max iterations are hit, exit. If the score plateaus across 3 iterations, exit.
5. **Tune** — the tuner avatar is given the AUT's transcript, the grader's feedback, and the agent's source bundle inside a `git worktree`. It can edit only the scopes in `allow_edit` (default: `prompt, skills, scripts`). It emits a unified diff with a one-line rationale.
6. **Apply** — the factory verifies the diff with `git apply --check`, applies it back to the agent's home path, and the next iteration begins.

Every iteration is also evaluated on the holdout split (the tuner never sees these scores). If train score rises while holdout falls, the tuner is overfitting and the loop pauses for review.

## Design principles

A few constraints are load-bearing — they're enforced by the CLI, not just conventions:

- **Factory and car are separated.** Agents are bundles at user-chosen paths, never inside `agent-maker/`. The factory operates on agents by path.
- **Agents ship bare.** The avatar overlay contains only the agent's own files. No factory tooling leaks into a running agent. What runs locally is what ships.
- **Graders never see transcripts.** Only the task + expected + output. Prevents the grader from being charmed by the AUT's reasoning.
- **Tuner is sandboxed.** Two layers: `settings.json` permission denies plus a `git worktree` rooted at the agent's path. Tuner output must pass `git apply --check` before being applied.
- **Overfit detection is built in.** The holdout split's score is reported every iteration but invisible to the tuner.

## The factory (this repo)

```
agent-maker/
├── cli/                            # jbang sources (Am.java + cmd/*.java)
├── templates/{agent,task,grader,tuner}/   # blueprints for `am new`
├── graders/                        # built-in graders (agent-shaped)
├── tuners/                         # built-in tuners (agent-shaped)
├── samples/                        # example bundles for testing the factory itself
├── runs/<task>/<agent>/<run>/      # per-iter transcripts, grades, diffs
├── docs/schemas/                   # agent.yaml, task.yaml, grade.json contracts
├── jbang-catalog.json
└── (drom-flow files: CLAUDE.md, .claude/, context/, drom-plans/, workflows/, scripts/)
```

## What an agent bundle looks like (the car)

```
<wherever>/<agent>/
├── agent.yaml                  # id, model (default opus), allow_edit, tool_allowlist, mcp
├── system-prompt.md            # appended via claude --append-system-prompt
├── skills/<skill>/<skill>.md   # claude-code skill format
├── scripts/                    # python + shell tools the agent invokes
├── hooks/                      # optional agent-specific lifecycle hooks
├── mcp/servers.json
└── memory/MEMORY.md
```

No drom-flow references. Self-contained. Ships anywhere.

## Install

Requires [jbang](https://www.jbang.dev/) and [claude-code](https://docs.claude.com/claude-code).

```bash
jbang app install am@agent-maker
```

## Quick start

```bash
# Scaffold a new agent outside the factory
am new agent my-bot --at ~/my-agents/my-bot

# Run it interactively (avatar mode)
am avatar ~/my-agents/my-bot

# Run it on a task headlessly and grade the result
am run ~/my-agents/my-bot --task ./tasks/some-task

# Tune it in a closed loop until it converges
am tune ~/my-agents/my-bot --task ./tasks/some-task --max-iters 10 --target-score 0.9

# Package for shipment
am publish ~/my-agents/my-bot --to ~/dist/my-bot-0.1.0.zip
```

## Full runbook

For the end-to-end recipe — scaffold an agent, tune it, package it, deploy it on a clean target machine — see **[docs/SHIPPING.md](docs/SHIPPING.md)**.

## Commands

| Command | Purpose |
|---|---|
| `am new agent\|task\|grader\|tuner <name> [--at <path>]` | Scaffold a new bundle |
| `am avatar <agent-path>` | Launch claude-code "as" the agent (interactive or headless `-p`) |
| `am run <agent-path> --task <task-path>` | Headless AUT run + grader scoring, one iteration |
| `am tune <agent-path> --task <task-path>` | Closed-loop tuning with AUT/Grader/Tuner triad |
| `am factory <factory.yaml>` | Batch-tune many agents in parallel |
| `am publish <agent-path> [--to <dest>]` | Package the bundle as a zip |

See `docs/schemas/` for the `agent.yaml`, `task.yaml`, and grade JSON contracts.

## Defaults

- Model: **opus** for all three roles (AUT, Grader, Tuner). Override per agent in `agent.yaml`.
- Java: **17** (via jbang; only relevant at the factory side — agents are language-agnostic).
- Tuner edit scope: **`prompt, skills, scripts`** (Python and shell included). Wider scopes (`mcp`, `hooks`, `all`) require explicit `--allow-edit`.

## Roadmap

Build is chapter-based in [`drom-plans/build-agent-maker.md`](drom-plans/build-agent-maker.md):

1. **Foundations** — schemas, layout, jbang catalog, decisions log ✅
2. **`am new`** — scaffolding command + 4 templates + working sample
3. **`am avatar`** — bare impersonation overlay (interactive + headless)
4. **`am run`** — one-shot headless AUT run + grade
5. **`am tune`** — the full triadic closed loop
6. **`am factory` + `am publish`** — batch tuning + zip export

## Development

The factory uses [drom-flow](https://github.com/drompincen/drom-flow) as its consistency layer — closed-loop spec, `orchestrate.sh`, plan tracking, parallel-agent conventions, lifecycle hooks. drom-flow lives in this repo's `CLAUDE.md`, `.claude/`, `context/`, `drom-plans/`, `workflows/`, and `scripts/orchestrate.sh`.

**drom-flow stays in the factory.** It is intentionally NOT shipped with the agents the factory produces — agents must remain portable and dependency-free.

Architecture decisions are logged in [`context/DECISIONS.md`](context/DECISIONS.md).

## License

[MIT](LICENSE)

## Status

Pre-alpha. Chapter 1 complete; Chapter 2 (`am new`) next.
