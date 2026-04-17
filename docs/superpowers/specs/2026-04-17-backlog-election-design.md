# Backlog Election — Parallel Jury Design

**Date:** 2026-04-17
**Status:** Design approved, pending implementation plan
**Supersedes:** `2026-04-14-backlog-election-design.md` (that design used a two-phase shortlist + deep-score approach; this design is a simpler single-pass parallel jury)

## Goal

From the set `B` of open Backlog issues, elect the top 3 whose completion would most unblock or simplify other open issues — by parallel weighted voting of all TEAM.md subagents.

## Definitions

- `X` = all open GitHub issues in `bedavs/interlockSim`
- `B ⊆ X` = issues whose `milestone.title == "Backlog"`
- **Effect of `b ∈ B`**: how much completing `b` unblocks or simplifies other issues in `X`, as judged by each team subagent from their domain perspective

## Input Fetch

One fetch before agents are launched:

```bash
gh issue list --state open --limit 500 \
  --json number,title,labels,body,milestone,assignees,comments
```

Partition result into `X` (all) and `B` (milestone == Backlog). Pass the full serialized JSON to every agent as `IssueContext`.

## Voters and Authority Weights

Per TEAM.md decision authority hierarchy:

| Role | Weight |
|---|---|
| traffic-simulation-expert | 3 |
| kotlin-tech-lead | 2 |
| railway-civil-engineer | 2 |
| java-senior-dev | 1 |
| agent-architect | 1 |
| qa-engineer | 1 |
| kotlin-junior-dev | 0.5 |

## Agent Grading Protocol

All 7 agents are dispatched in a single parallel batch. Each agent:

1. Receives: `IssueContext` (full JSON of X) + role-specific persona from TEAM.md
2. Grades every `b ∈ B` on a 0–10 scale:
   - **0** — completing `b` has no dependency effect on any other X-issue
   - **5** — completing `b` simplifies or de-risks a few X-issues
   - **10** — completing `b` unblocks or directly enables many X-issues
3. Returns **only**: JSON `{ "issueNumber": score, ... }` — no explanation

Agents grade from within their defined domain (e.g., qa-engineer focuses on testing/quality issues; java-senior-dev focuses on legacy/null-safety debt).

## Aggregation

Orchestrator (main session) collects all 7 score maps and computes:

```
finalScore[b] = Σ_voter  weight(voter) × score(voter, b)
```

Sort by `finalScore` descending. Output the top 3 issue numbers.

## Output

Terminal only — three issue numbers, nothing else:

```
#NNN, #NNN, #NNN
```

## Non-Goals

- No GitHub writes, labels, or comments
- No persistent storage of scores
- No detailed rationale report (see 2026-04-14 design if full report is needed)
- No retry logic — malformed agent JSON scores that issue as 0

## Execution Model

Orchestrator dispatches 7 `Agent` tool calls in a single message (`subagent_type: general-purpose`). Each agent prompt includes:
- Role persona injected from TEAM.md
- Full `IssueContext` string
- Grading instructions above
- Strict output format requirement (JSON only)

Total: **7 agent calls** per election run.

## Differences from 2026-04-14 Design

| Aspect | 2026-04-14 | 2026-04-17 |
|---|---|---|
| Phases | Two (shortlist + deep score) | One (parallel jury) |
| Issue bodies | Phase-2 only | All upfront |
| Score rubric | 0–3 per edge | 0–10 per B-issue |
| kotlin-junior-dev | Excluded | Included (weight 0.5) |
| Output | Full report + 1 winner | Top 3 numbers only |
| Agent calls | ~70–100 | 7 |
