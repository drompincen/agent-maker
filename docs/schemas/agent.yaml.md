# `agent.yaml` schema

Every agent bundle has an `agent.yaml` at its root. It declares the agent's identity, runtime parameters, claude-code tool permissions, MCP servers, and the scope of edits the tuner is allowed to make.

## Schema

```yaml
id: my-agent                    # required — kebab-case, unique
version: 0.1.0                  # required — semver
description: |                  # required — one-paragraph summary
  What this agent does.

model: opus                     # default: opus
                                # values: opus | sonnet | haiku | <full model id>

# --- Persona / system prompt ---
# Primary identity lives in system-prompt.md (appended via --append-system-prompt).
# Optional inline persona is appended AFTER the main prompt.
persona: |                      # optional
  Additional persona notes.

# --- Tuner permissions ---
allow_edit:                     # default: [prompt, skills, scripts]
  - prompt                      # system-prompt.md
  - skills                      # skills/**/*.md
  - scripts                     # scripts/**
# - mcp                         # opt-in: mcp/servers.json
# - hooks                       # opt-in: hooks/**
# - all                         # opt-in: everything in the bundle

# --- claude-code tool permissions (becomes settings.json permissions) ---
tool_allowlist:                 # optional
  allow:
    - Read
    - Write
    - Edit
    - Bash(python *)
  deny:
    - Bash(rm -rf *)
    - Bash(curl http*)

# --- MCP servers ---
mcp_servers:                    # optional
  - name: my-mcp
    command: python
    args: ["-m", "my_mcp_server"]

# --- Lifecycle hooks (claude-code settings.json format) ---
hooks:                          # optional
  PostToolUse:
    - matcher: "Edit"
      command: scripts/post-edit.sh

# --- Memory seed ---
memory_seed: memory/MEMORY.md   # default; relative to bundle root
```

## Required fields

- `id`, `version`, `description`

## Defaults

- `model: opus`
- `allow_edit: [prompt, skills, scripts]`
- `memory_seed: memory/MEMORY.md`

## Allow-edit semantics

`allow_edit` declares what the **tuner** is permitted to modify. `am tune --allow-edit <scopes>` overrides the agent's default at runtime, but the per-tune scope cannot be wider than the agent's declared scope unless `--allow-edit all` is explicitly passed AND the agent's declared scope includes `all`.

| Scope | Tuner may write to |
|---|---|
| `prompt` | `system-prompt.md` only |
| `skills` | `skills/**/*.md` |
| `scripts` | `scripts/**` (typically Python + shell) |
| `mcp` | `mcp/servers.json` |
| `hooks` | `hooks/**` |
| `all` | every file in the bundle |

Enforcement at runtime is double-layered: (a) the tuner avatar's `settings.json` denies writes outside the scope, and (b) the tuner runs inside a `git worktree` rooted at the agent's path so even bypassed writes can't escape.

## How `agent.yaml` becomes a running avatar

`am avatar <path>` reads `agent.yaml` and synthesizes a `settings.json` into an ephemeral `$CLAUDE_CONFIG_DIR`:

- `tool_allowlist` → `permissions.allow` / `permissions.deny`
- `mcp_servers` → `mcpServers`
- `hooks` → `hooks`
- `model` → `--model` flag on the `claude` invocation
- `system-prompt.md` → `--append-system-prompt`
- `skills/` → symlinked into `$CLAUDE_CONFIG_DIR/skills/`
- `memory_seed` → copied into `$CLAUDE_CONFIG_DIR/MEMORY.md`

No drom-flow files enter the overlay. The avatar is deployment-equivalent.
