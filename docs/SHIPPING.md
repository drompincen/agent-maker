# Shipping an agent — the runbook

End-to-end recipe: scaffold an agent → tune it against a task → package it as a zip → run it on a clean target machine.

## Prerequisites

- [jbang](https://www.jbang.dev/) installed on the factory machine (and any deploy target)
- [claude-code](https://docs.claude.com/claude-code) installed and on `PATH` on both factory and target
- Anthropic credentials configured for claude-code on both machines
- A checkout of agent-maker on the factory machine

> One-time: `jbang app install am@agent-maker` to drop the `jbang cli/Am.java` prefix.

---

## Step 1 — Scaffold a new agent

Agents are portable bundles; they do NOT live inside `agent-maker/`.

```bash
am new agent my-bot --at ~/agents/my-bot
```

You get:

```
~/agents/my-bot/
├── agent.yaml          # id, model (opus default), allow_edit, permissions, MCP
├── system-prompt.md    # the agent's identity
├── memory/MEMORY.md    # seed memory
├── skills/             # claude-code skills
├── scripts/            # python + shell tools
├── hooks/              # optional lifecycle hooks
└── mcp/                # optional MCP servers
```

`am new task|grader|tuner` works the same way. See `am new --help`.

## Step 2 — Write the agent's identity

Edit `~/agents/my-bot/system-prompt.md` — appended to claude's system prompt at every avatar launch. State role, goals, operating rules, output expectations.

Edit `~/agents/my-bot/agent.yaml`:
- Update `description`.
- Tighten `tool_allowlist.allow` / `.deny` to the minimum tools the agent needs.
- Add `mcp_servers` for external integrations.
- Override `model` (default: opus) for sonnet/haiku.

Add capabilities under `skills/<skill>/<skill>.md` (claude-code skill format) and tools the agent shells out to under `scripts/`.

## Step 3 — Try it interactively

```bash
am avatar ~/agents/my-bot                 # interactive
am avatar ~/agents/my-bot -p "do task X"  # headless single-prompt
```

Note what the agent gets wrong — those are the issues the tuner will target.

## Step 4 — Define a task + grader

Tuning needs (a) a task with input artifacts + expected outputs, and (b) a grader that scores the output.

```bash
am new task my-task --at ~/tasks/my-task
# drop inputs into       ~/tasks/my-task/artifacts/
# drop gold/rubric into  ~/tasks/my-task/expected/
# edit                   ~/tasks/my-task/task.yaml  (instructions, grader path, target_score)

am new grader my-grader   # creates inside the factory at graders/my-grader
# edit                   graders/my-grader/system-prompt.md  (scoring rules)
```

Point the task at the grader by relative path in `task.yaml`:

```yaml
grader: ../../../graders/my-grader
```

Grader output contract: a single `output/grade.json` per `docs/schemas/grade.json.md`. The grader never sees the AUT's transcript — only the task + expected + AUT's output. That isolation is load-bearing.

## Step 5 — Run once + read the score

```bash
am run ~/agents/my-bot --task ~/tasks/my-task
```

```
[am run] AUT    → ~/agents/my-bot
[am run] task   → ~/tasks/my-task
[am run] grader → ~/graders/my-grader
[am run] iter   → runs/my-task/my-bot-<hash>/<stamp>/iter-1
[am run] score=0.7 pass=false issues=2
[am run] <rationale>
```

Inspect `runs/.../iter-1/` for `transcript.jsonl`, `output/`, and `grade.json`.

## Step 6 — Tune in a closed loop

```bash
am tune ~/agents/my-bot \
  --task ~/tasks/my-task \
  --max-iters 10 \
  --target-score 0.9
```

Each iteration: AUT runs → grader scores → if not converged, tuner edits the bundle (within `--allow-edit` scopes, default `prompt,skills,scripts`) → repeat. Convergence exits on `score >= target_score`, grader `pass: true`, score plateau over last 3 iters, or tuner made no in-scope changes.

Watch `runs/.../SUMMARY.md` for the per-iter score table and exit reason.

> **Cost discipline**: opus × 3 avatars × N iters adds up fast. Override `model: sonnet` for cheaper graders/tuners in their `agent.yaml`; cap `--max-iters` aggressively; pick a `--target-score` you can actually accept.

## Step 7 — Publish the tuned bundle

```bash
am publish ~/agents/my-bot
# → <factory>/dist/my-bot-0.1.0.zip
```

Or to a specific path:

```bash
am publish ~/agents/my-bot --to ~/releases/my-bot-0.1.0.zip
```

The zip is the bundle exactly as it sits — no factory tooling, no drom-flow.

## Step 8 — Deploy on the target machine

**Easiest**: unzip + run via agent-maker on the target.

```bash
# On the target
unzip my-bot-0.1.0.zip -d /opt/agents/my-bot
jbang app install am@agent-maker          # one-time
am avatar /opt/agents/my-bot              # interactive
am avatar /opt/agents/my-bot -p "do X"    # headless
```

**Without agent-maker on the target** (bare launcher):

```bash
export CLAUDE_CONFIG_DIR=$(mktemp -d)
cp -r /opt/agents/my-bot/skills "$CLAUDE_CONFIG_DIR/" 2>/dev/null || true
cp -r /opt/agents/my-bot/hooks  "$CLAUDE_CONFIG_DIR/" 2>/dev/null || true
cp    /opt/agents/my-bot/memory/MEMORY.md "$CLAUDE_CONFIG_DIR/CLAUDE.md" 2>/dev/null || true
# settings.json synthesis is the one thing you'd hand-roll without agent-maker.
claude --append-system-prompt @/opt/agents/my-bot/system-prompt.md \
       --model claude-opus-4-7 \
       -p "your prompt"
```

For repeatable deploys, install agent-maker on the target — less to maintain.

---

## Batch-tune many agents

If you have many agents/tasks, write a `factories/<name>.yaml`:

```yaml
version: 0.1.0
agents:
  - agent: ~/agents/extractor-invoices
    task:  ~/tasks/invoices
    max_iters: 8
    target_score: 0.95
  - agent: ~/agents/extractor-receipts
    task:  ~/tasks/receipts
    max_iters: 8
    target_score: 0.95
    allow_edit: prompt,skills,scripts
```

```bash
am factory factories/my-fleet.yaml --parallel 4
```

Per-job logs land under `runs/.factory/`. Aggregate report at `factories/<name>-<stamp>-report.md`.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `failed to exec claude — is it installed and on PATH?` | claude-code binary missing | install claude-code; ensure `claude` is on `PATH` for the jbang process |
| `Cannot find factory root` | running outside the factory and no `AGENT_MAKER_HOME` | `export AGENT_MAKER_HOME=/path/to/agent-maker` or `cd` into the factory |
| `grader did not produce grade.json` | grader's system prompt is off; or its tool permissions deny Write | check the grader's `system-prompt.md` and `tool_allowlist` |
| Tune stops at "tuner made no in-scope changes" | tuner's edits all fell outside `--allow-edit` scopes | widen scopes, e.g. `--allow-edit prompt,skills,scripts,mcp` |
| Tuning regresses on held-out tasks | overfitting to the train task | run multi-task tuning via `am factory`, or rotate tasks per iter |
| `UNC paths are not supported` | Windows jbang invoked against a WSL `/tmp` path | stay on `/mnt/c/...` paths, or install jbang natively inside WSL |

---

## Trivial end-to-end smoke (no real agent needed)

Verify the pipeline before applying it to a real agent:

```bash
am avatar  samples/echo-bot -p "say hi"
am run     samples/echo-bot --task samples/tasks/say-hi
am tune    samples/echo-bot --task samples/tasks/say-hi --max-iters 2 --target-score 1.0
am publish samples/echo-bot --to /tmp/echo-bot-test.zip
```

All four should land artifacts under `runs/say-hi/echo-bot-<hash>/` plus a zip in `/tmp/`.
