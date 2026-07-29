# Actuator Tool Return Values Analysis

Issue: #819  
Related context: #814

## Conclusion

The three actuator tools exposed to the LLM — `request_route`, `release_route`, and
`approve_train` — are intentionally **fire-and-forget**. Their successful tool-call result
means only that a command was accepted by the in-memory `ActuatorCommandQueue`; it does not
mean that the requested railway action succeeded.

The LLM receives a plain queued-command acknowledgement:

| Tool | Successful LLM-facing result |
| --- | --- |
| `request_route` | `queued request_route train=<train> from=<from> to=<to>` |
| `release_route` | `queued release_route train=<train>` |
| `approve_train` | `queued approve_train trainId=<train>` |

The real result is computed later on the kDisco simulation thread and is logged, but is not
returned to the initiating tool call or otherwise published as a correlated outcome.

## Return-value layers

There are two distinct contracts:

| Layer | Values | When computed | Visible to the LLM |
| --- | --- | --- | --- |
| Domain actuator ports | `RouteRequestResult` or `Boolean` | Synchronously when the queued command is applied on the simulation thread | No |
| Domain tools / Koog adapter | `ToolResult.Success` with a queued-command string, or `ToolResult.Error` | Immediately on the agent driver thread | Yes |

`NetworkActuatorPort.requestRoute()` returns one of `Reserved`, `AllPathsBlocked`, `Conflict`,
or `NoRouteExists`. `releaseRoute()`, `setSwitchPosition()`, and `setSignalAspect()` return a
boolean. These are rich, synchronous port-level outcomes, but they are not the tool results
for LLM calls.

The only currently registered LLM actuator tools are the three listed above. Direct
`set_switch_position` and `set_signal_aspect` tools were deliberately removed because they
would let the model manipulate low-level infrastructure outside the reservation flow.

## Execution path

1. Koog invokes `KoogToolAdapter.execute()`.
2. The adapter invokes the relevant `DomainTool.execute()`.
3. The tool validates immediately available input, creates a `DispatchDecision`, and posts it to
   `ActuatorCommandQueue`.
4. A successful post returns `ToolResult.Success` containing the queued-command string.
5. `KoogToolAdapter` converts that string directly into the tool response shown to the LLM.
6. At a later simulation control step, `DispatchDecisionApplier` drains the queue and performs
   the real actuation on the kDisco thread.
7. The actuator result is used for logging and internal handling only.

This separation is necessary because the agent driver must not read or mutate live simulation
state off the kDisco simulation thread.

## Errors visible at tool-call time

The LLM can receive `ToolResult.Error`, mapped by `KoogToolAdapter` to
`ToolException.ValidationFailure`, for failures detectable before queueing:

- missing, blank, or incorrectly typed arguments;
- an unknown `request_route` endpoint, checked against static topology;
- a full actuator queue;
- an `approve_train` request rejected by its snapshot-based concurrent-train-cap check; or
- an exception from `DispatchLoopActuatorPort.approveTrain()`.

These errors should not be confused with the eventual simulation outcome. In particular,
`request_route` may be accepted into the queue but later produce `AllPathsBlocked`, `Conflict`,
or `NoRouteExists`.

## Outcomes not returned to the LLM

After a command is queued, the following results are not returned to the tool caller:

| Command | Actual result | Current handling |
| --- | --- | --- |
| Route request | `Reserved`, `AllPathsBlocked`, `Conflict`, or `NoRouteExists` | Logged by `applyToolDrivenToActuator()` |
| Route release | Whether any reservation was released | Logged by `applyToolDrivenToActuator()` |
| Train approval | Whether the queued train was actually admitted | Callback returns `Unit`; absent or already-active trains are idempotently ignored |
| Late invalid command | `IllegalArgumentException` while applying a queued decision | Caught and logged by `DispatchDecisionApplier` |

The documented recovery mechanism is to inspect the next snapshot using perception tools such as
`block_occupancy`, `all_block_occupancies`, `all_train_positions`, and `queued_trains`.
Snapshots can be one simulation tick stale and provide state rather than a correlated reason for
a particular prior request.

## Critical assessment

### What works

- Queue acknowledgement gives prompt feedback and preserves the strict simulation-thread
  boundary.
- Invalid endpoint names are rejected before queueing, preventing one common invisible failure.
- The queue's per-cycle post counter correctly distinguishes a tool call that was accepted for
  later application from a cycle where the LLM did nothing; this avoids unnecessary fallback
  dispatching.
- Existing tests explicitly verify that actuator tools return queue acknowledgement rather than
  post-application effects.

### Risks

1. **A `Success` result can be misread as an actuation success.** The word `queued` is present,
   but an LLM can still treat it as confirmation that a route was reserved or a train was
   admitted.
2. **Post-queue failures have no direct feedback channel.** A route conflict, blocked route, or
   late validation failure is only logged. The next model cycle must infer it from changed (or
   unchanged) observations.
3. **Approval has the weakest confirmation path.** `approve_train` reports queue acceptance,
   while its actual application has no returned boolean and is idempotently ignored for an
   absent or already-active train.
4. **The snapshot admission-cap check is inherently stale.** Multiple quick `approve_train`
   calls can observe the same pre-application active-train count and each receive queued
   acknowledgement.
5. **Stateless dispatch cycles compound the gap.** A later cycle can observe current state, but
   cannot directly associate an observed outcome with its earlier actuator request.

These limitations help explain why #814 requires a deterministic admission safety net: queue
acceptance alone is insufficient evidence that an intended admission took effect.

## Recommendations

No implementation change is required to answer #819: the current queued-only result is
intentional and covered by tests. Before changing the contract, decide whether asynchronous
actuation feedback is a product requirement.

If richer feedback is needed, preserve the simulation-thread boundary and add an asynchronous,
bounded outcome channel rather than blocking a tool call. For example:

1. Publish recent applied-command outcomes, including rejection reasons, into the
   snapshot-projected perception surface.
2. Include a correlation identifier in both the queued acknowledgement and the published
   outcome.
3. Add a perception tool that returns those outcomes for a train or correlation identifier.
4. Publish `IllegalArgumentException` drops through the same channel rather than logging them
   only.
5. Enforce the concurrent-train cap again when an `ApproveTrain` decision is applied on the
   simulation thread, where it can use current state rather than the driver's stale snapshot.

This would let a subsequent agent cycle learn whether a queued action actually succeeded without
allowing the agent driver thread to access mutable simulation state.

## Evidence

- `dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/agents/DomainTool.kt`
  — tool threading and result contract.
- `dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/agents/KoogToolAdapter.kt`
  — `ToolResult` conversion to LLM-facing strings/errors.
- `dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/agents/tools/RequestRouteTool.kt`
  — queued route-request result and pre-queue validation.
- `dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/agents/tools/ReleaseRouteTool.kt`
  — queued release result.
- `dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/agents/tools/ApproveTrainTool.kt`
  — queued approval result and snapshot-based cap check.
- `dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/ActuatorCommandQueue.kt`
  — queue acceptance semantics and per-cycle post count.
- `dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/DispatchDecisionApplier.kt`
  — deferred application and late-invalid-command logging.
- `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/ports/NetworkActuatorPort.kt`
  — synchronous port-level outcome contracts.
- `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/ToolDrivenDecisions.kt`
  — actual outcomes are logged after application.
- `dispatcher-agent/src/test/kotlin/cz/vutbr/fit/interlockSim/dispatcher/agents/ActuatorToolExecuteTest.kt`
  and `dispatcher-agent/src/test/kotlin/cz/vutbr/fit/interlockSim/dispatcher/ActuatorToolMarshallingIntegrationTest.kt`
  — tests of queued acknowledgement and deferred application.
