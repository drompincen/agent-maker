# `task.yaml` schema

A task is the unit of work an AUT is given. It bundles instructions, input artifacts, expected outputs, the grader reference, and tuning targets.

## Schema

```yaml
id: extract-invoice-data         # required — kebab-case
version: 0.1.0                   # required — semver
description: |                   # required
  Extract structured line items from invoice PDFs.

instructions: |                  # required — given to the AUT verbatim
  You will be given an invoice PDF in artifacts/. Extract every line item
  as JSON with fields {description, quantity, unit_price, total} and write
  the result to output/items.json.

artifacts_dir: artifacts/        # default; relative to task dir
expected_dir: expected/          # default; relative to task dir

grader: ../graders/invoice-grader   # required — path to grader bundle (relative or absolute)
                                    # or a built-in name like "contains-hello"
target_score: 0.9                # default: 0.9

# --- Splits for anti-overfit ---
splits:                          # optional but recommended for tuning
  train:
    - artifacts/sample-1.pdf
    - artifacts/sample-2.pdf
  dev:
    - artifacts/sample-3.pdf
  holdout:
    - artifacts/sample-4.pdf
    - artifacts/sample-5.pdf

# --- Run-time bounds ---
timeout_seconds: 300             # default: 300 — per AUT run
```

## Required fields

- `id`, `version`, `description`, `instructions`, `grader`

## Directory layout

```
<task>/
├── task.yaml
├── artifacts/        # inputs the AUT reads (PDFs, images, JSON, text — any files)
├── expected/         # gold answers, rubrics, schemas the grader uses
└── splits.yaml       # (optional) richer split definition than the inline form
```

## How tasks are presented to the AUT

`am run` mounts the task into the AUT avatar's working directory:

- `artifacts/` is exposed read-only at the AUT's CWD
- `output/` is created empty and is the AUT's writable workspace
- `instructions` becomes the user message sent to `claude -p`

Everything outside the task's working dir is invisible to the AUT.

## How splits drive tuning

When `am tune --holdout dev|holdout` is passed:

- The tuner sees scores ONLY on the `train` split (so its edits target train performance).
- `dev` / `holdout` scores are computed every iteration and reported separately to `runs/.../iter-NN/holdout.json`.
- If train score rises while holdout falls, the tuner is overfitting — `am tune` flags this and pauses if the gap exceeds a threshold.

For multi-task tuning, the tuner cycles tasks per iter or picks the worst-scoring task — preventing overfit to a single task.
