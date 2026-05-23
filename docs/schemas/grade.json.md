# Grader output contract (`grade.json`)

Graders are agents whose output MUST be a single JSON object matching this contract. Validation happens at `am run` and `am tune` time; non-conforming output fails the run loudly.

## Schema

```json
{
  "score": 0.85,
  "pass": false,
  "issues": [
    {
      "severity": "major",
      "area": "output format",
      "msg": "Line item amounts are strings, not numbers",
      "suggestion": "Cast to float during extraction"
    }
  ],
  "rationale": "Most fields extracted correctly but amount types violate the schema."
}
```

## Fields

- `score` — float in `[0.0, 1.0]`. Higher is better.
- `pass` — boolean. The grader's binary judgment. `score >= target_score` is the common rule, but the grader may set `pass` independently (e.g., a critical issue can fail at any score).
- `issues` — array (may be empty). Each issue:
  - `severity` — `"major" | "minor" | "nit"`
  - `area` — short free-text label (e.g., `"extraction accuracy"`, `"output format"`)
  - `msg` — what's wrong
  - `suggestion` — optional; what would fix it
- `rationale` — single paragraph explaining the overall assessment

## What the grader sees

When `am run` or `am tune` invokes the grader avatar, it provides ONLY:

1. The task's `instructions` (verbatim)
2. The task's `expected/` directory contents (gold answers, rubrics, schemas)
3. The AUT's `output/` directory contents

The grader **does NOT** see the AUT's `transcript.jsonl`. This is a load-bearing isolation — it prevents the grader from being charmed by the AUT's reasoning into rating poor output favorably.

## How the grader emits the JSON

The grader avatar must write `grade.json` to its `output/` directory as the last step of its run. The factory reads and parses that file; nothing else in the grader's output is consumed.

A simple grader system prompt enforces this contract:

> Read `instructions.md`, `expected/`, and `output/` (the AUT's submission). Score it. Write your judgment as a single JSON object to `output/grade.json` matching the contract in `grade.json.md`. Do not write any other files. Do not print to stdout.

## Validation

`am run` / `am tune` validate `grade.json` against this contract before using it. Any of these fail the run:

- File missing
- Invalid JSON
- `score` outside `[0.0, 1.0]`
- Missing required fields (`score`, `pass`, `issues`, `rationale`)
- `severity` not in `{major, minor, nit}`

A failing grader is treated as a tuner-actionable defect — the grader bundle itself can be tuned in a separate run.
