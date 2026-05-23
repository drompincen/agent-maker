# agent-maker

A factory for producing portable claude-code agents.

## The metaphor

**agent-maker is the factory. Agents are the cars.** Cars roll off the assembly line and drive away. The factory keeps the tools; it does not garage finished cars.

Agents produced by this factory are **portable bundles** — self-contained directories of `agent.yaml`, `system-prompt.md`, `skills/`, `scripts/`, `hooks/`, `mcp/`, and `memory/`. They live at user-chosen paths, run on any machine with claude-code, and have **no dependency** on this factory or on its internal tooling.

## The triadic loop

The core workflow is a closed loop across three roles:

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

drom-flow is installed here as the factory's consistency layer — closed-loop, orchestrate.sh, plans, hooks, skills. **drom-flow is NOT shipped with the agents the factory produces.**

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
- Java: **17** (via jbang; only matters at the factory side — agents are language-agnostic bundles).
- Tuner edit scope: **`prompt, skills, scripts`** (Python and shell scripts included). Wider scopes (`mcp`, `hooks`, `all`) require explicit `--allow-edit`.

## Status

Pre-alpha. Build plan: `drom-plans/build-agent-maker.md`.
