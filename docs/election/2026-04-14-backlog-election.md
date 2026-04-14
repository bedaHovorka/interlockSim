# Backlog Election — 2026-04-14

Weighted election of the single Backlog-milestone issue whose completion
would most help all other open GitHub issues. Voters are the TEAM.md
subagents; effect is judged semantically on dependency/leverage, with
authority-based voter weights.

- Spec: `docs/superpowers/specs/2026-04-14-backlog-election-design.md`
- Runbook: `docs/superpowers/plans/2026-04-14-backlog-election.md`

## Ranking (Shortlist S, |S|=10)

| Rank | #   | Title                                                              | Total | TSE×2 | KTL×1.5 | JSD×1.5 | AA×1 | RCE×1 | QA×1 |
|------|-----|--------------------------------------------------------------------|-------|-------|---------|---------|------|-------|------|
| 1    | 365 | Add ShuntingLoop instrumentation for test observability            | 68.5  | 9     | 7       | 10      | 11   | 8     | 6    |
| 2    | 366 | Individual train movement tests with dedicated test process        | 59.0  | 9     | 5       | 7       | 8    | 8     | 7    |
| 3    | 386 | Code quality follow-up from PR #385 :core KMP extraction review    | 58.5  | 5     | 8       | 11      | 9    | 5     | 6    |
| 4    | 376 | SonarCloud: improve new-code coverage ≥80% quality gate            | 43.0  | 4     | 6       | 8       | 5    | 5     | 4    |
| 5    | 405 | XML: XmlContextReader silently skips unknown elements              | 40.0  | 5     | 4       | 6       | 5    | 6     | 4    |
| 6    | 248 | XML serialization does not preserve context properties             | 37.5  | 6     | 4       | 5       | 4    | 5     | 3    |
| 7    | 397 | Generator: reuse seeded RNG instance for shuffle() across calls    | 33.0  | 3     | 4       | 2       | 13   | 3     | 2    |
| 8    | 435 | fast-sim: support configurable simulation process in sim mode      | 31.5  | 3     | 3       | 4       | 7    | 6     | 2    |
| 9    | 406 | ktlint not applied to :core subproject                             | 25.5  | 2     | 5       | 2       | 4    | 3     | 4    |
| 10   | 407 | Purity gate: whitelist approach is fragile                         | 17.5  | 2     | 3       | 2       | 3    | 1     | 2    |

## Winner: #365 — Add ShuntingLoop instrumentation for test observability (total 68.5)

Exposes `getTrainsEntered()`, `getTrainsExited()`, `getMaxConcurrentTrains()`,
and per-train block-transition metrics from `sim/ShuntingLoop` — turning an
opaque black-box into an assertable surface.

Top issues helped by #365:

| #   | Title                                            | score | rationale (best voter)                                                           |
|-----|--------------------------------------------------|-------|----------------------------------------------------------------------------------|
| 366 | Individual train movement tests (dedicated proc) | 2     | TSE: instrumentation API shape informs what the dedicated test process exposes   |
| 195 | Phase 4.1: Golden Output Tests                   | 2     | TSE: golden output tests require metrics to assert against baseline              |
| 198 | Phase 4.4: Regression Testing                    | 2     | TSE: regression tests need these metrics to detect behavioral drift              |
| 453 | Increase test coverage — next volume             | 2     | RCE: instrumentation enables assertions that materially improve coverage         |
| 196 | Phase 4.2: Performance Benchmarks                | 2     | AA:  performance benchmarks need instrumentation hooks                           |
| 197 | Phase 4.3: Integration Tests                     | 2     | AA:  integration tests need ShuntingLoop observability                           |
| 376 | SonarCloud new-code coverage gate                | 1     | JSD: helps new-code coverage on sim paths                                        |
| 187 | Goal 7: Simulation Speed Control (0.1x–100x)     | 1     | JSD: speed-control verification benefits from train metrics                      |
| 435 | fast-sim configurable sim process                | 1     | RCE: clarifies what a configurable process must expose for observability         |

## Runners-up

### #2 — #366 Individual train movement tests with dedicated test process (total 59.0)

Creates a `SimpleLinearTrackTestProcess` that isolates individual Train
lifecycle for direct testing. Tightly coupled with the winner: #365 defines
the observability surface, #366 consumes it. Strongest impact on Phase
4.3/4.4 integration and regression suites and on raising `Train.kt`
coverage for #376/#453.

### #3 — #386 Code quality follow-up from PR #385 :core KMP extraction (total 58.5)

Umbrella cleanup for the :core KMP extraction: fixes stale
`sonar-project.properties` (prerequisite for #376/#453 coverage measurement
to be accurate), addresses build/KDoc hygiene that #406/#407/#412 depend
on, and unblocks SonarQube reliability rating from the #385 merge.

## Methodology

Voters (TEAM.md):

- traffic-simulation-expert (×2.0, arbiter)
- kotlin-tech-lead          (×1.5)
- java-senior-dev           (×1.5)
- agent-architect           (×1.0)
- railway-civil-engineer    (×1.0)
- qa-engineer               (×1.0)

Scale: `|X|` = 38 open issues, `|B|` = 24 Backlog-milestone issues,
`|S|` = 10 shortlist candidates.

Process:

- Phase 1 (shortlist): 6 agent calls. Each voter independently picked
  top-5 backlog candidates from titles/labels only. Union built the
  shortlist.
- Phase 2 (scoring): 6 agent calls. Each voter scored all 10 shortlist
  candidates against every `x ∈ X` using the 1/2/3 rubric with full
  issue bodies. Batched per voter to cut the planned `|S|×6=60`-call
  matrix to 6.
- Aggregation: `total(b) = Σ voter-weight × raw(b, voter)`.

Total agent calls: 12. Abstentions (malformed JSON after retry): none.

Note: six voters also nominated issue #188 ("Phase 1.1: Core
SimulationRunner") in Phase 1, but #188 is not in the Backlog milestone
(it belongs to the AnimatedSim phase milestones) and was therefore
excluded from the shortlist. If the election scope is ever broadened
beyond Backlog, #188 would be a strong candidate.
