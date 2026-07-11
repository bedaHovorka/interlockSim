# Backlog Election by Team Subagents — Design

**Date:** 2026-04-14
**Status:** Design approved, pending user review

## Goal

Elect the single backlog issue whose completion would most help all other open
issues, via weighted subagent voting on semantic dependency impact.

## Definitions

- `X` = all open GitHub issues in `bedaHovorka/interlockSim`
- `B ⊆ X` = issues whose milestone is `Backlog`
- **Effect of `b ∈ B`**: the aggregated, voter-weighted degree to which
  completing `b` would help (unblock, simplify, enable, de-risk) other issues
  in `X`, as judged semantically by qualified team subagents.

## Inputs

Fetch with `gh`:

```bash
gh issue list --state open --limit 500 \
  --json number,title,labels,body,milestone,assignees
```

Partition into `X` (all) and `B` (milestone.title == "Backlog").

## Voters and Weights

Per TEAM.md authority hierarchy:

| Role | Weight |
|---|---|
| traffic-simulation-expert (arbiter) | 2.0 |
| kotlin-tech-lead | 1.5 |
| java-senior-dev | 1.5 |
| agent-architect | 1.0 |
| railway-civil-engineer | 1.0 |
| qa-engineer | 1.0 |

kotlin-junior-dev roles do not vote (implementers, not judges).

## Two-Phase Process

### Phase 1 — Shortlisting (cheap)

Dispatch all 6 voters in parallel. Each receives:

- Full list of `B` as `(number, title, labels)` tuples (no bodies)
- Short list of `X` as `(number, title, labels)` tuples (context only)
- Instruction: return top-5 backlog candidates whose completion would most
  leverage the rest of the open issue set, with one-line rationale each.

Union the six top-5 lists into shortlist `S`. Expected `|S| ∈ [10, 15]`.

### Phase 2 — Weighted Scoring (bounded)

For each `b ∈ S`, dispatch each of the 6 voters in parallel. Each receives:

- Full body of `b`
- Full bodies of all `x ∈ X` (batched; voter may chunk internally)
- Instruction: return a JSON list `[{x: number, score: 0..3, why: "..."}]`
  including only edges with `score > 0`.

Scoring rubric:
- **0** — no meaningful help
- **1** — marginal/indirect help
- **2** — clear prerequisite or substantial simplification
- **3** — hard blocker: `x` cannot be done (or is pointless) until `b` ships

Per-candidate raw score from one voter:
```
raw(b, voter) = Σ scores returned by voter for b
```

Weighted total:
```
total(b) = Σ_voter weight(voter) × raw(b, voter)
```

### Phase 3 — Report (terminal only)

No GitHub writes, no commits, no labels. Print to terminal:

1. **Ranking table**: rank, issue #, title, `total`, per-voter raw scores
2. **Winner section**: issue #, title, `total`; top-10 most-helped `x` issues
   with the best one-liner rationale from any voter per edge
3. **Runners-up**: next 2 with brief summary
4. **Methodology footer**: voter list, weights, shortlist size `|S|`,
   pair count evaluated, total agent calls

## Non-Goals

- No caching of results across runs (election is a point-in-time snapshot)
- No automated issue labeling or commenting
- No persistent storage of scores
- No election of multiple winners (single winner + runners-up only)

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Voter returns malformed JSON | Validate per voter; retry once; else score 0 |
| Voter timeout on large `X` | Phase-2 voters may chunk `X` internally; they own batching |
| Shortlist union too large (>20) | Cap `S` at 20 by taking highest-frequency picks across voters |
| Single voter dominates via inflated scores | Rubric caps at 3/edge; weight already bounds influence |
| `|X|` very large (>100) | Phase-1 payload stays cheap (titles only); Phase 2 cost scales with `|S|×voters`, not `|B|×voters` |

## Execution Model

Orchestrator (main Claude session) dispatches subagents via `Agent` tool with
`subagent_type: general-purpose` and a role-specific persona injected in the
prompt referencing TEAM.md. All Phase-1 voters run in one parallel batch;
Phase 2 runs one parallel batch per `b ∈ S` (or one flat batch of `|S|×6`
if agent concurrency allows).

## Estimated Cost

- Phase 1: 6 agent calls
- Phase 2: `|S| × 6` agent calls, expected 60–90
- Total: ~70–100 agent calls per election

## Deliverable

Terminal-only textual report, structured as specified in Phase 3.
