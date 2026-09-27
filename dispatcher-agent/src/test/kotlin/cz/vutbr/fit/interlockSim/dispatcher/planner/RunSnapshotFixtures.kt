/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor

/**
 * Builds a synthetic [DispatcherRunSnapshot] for report tests: one `LLM_ACTIONS` tick (plus
 * [ruleFallbackTicks] `RULE_FALLBACK` ticks), every other counter zero, and every parameter
 * overridable. Shared by [RunReportAggregatorTest], [DispatcherReliabilityReportTest] and
 * [DefaultRunSnapshotStoreTest].
 */
internal fun runSnapshot(
	runId: String = "test-run",
	arm: DispatcherArm = DispatcherArm.RULE_BASED,
	completedNaturally: Boolean = true,
	fallback: Boolean = false,
	c7Clean: Boolean = true,
	llmSuccessRate: Double = 1.0,
	actionableTickRate: Double = llmSuccessRate,
	noOpRate: Double = 0.0,
	invalidOutputRate: Double = 0.0,
	repairSuccessRate: Double = 0.0,
	latencyP50Ms: Long? = 100L,
	latencyP95Ms: Long? = 200L,
	latencyMaxMs: Long? = 300L,
	rejections: Map<RejectionCode, Long> = emptyMap(),
	applyFailures: Map<ApplyFailureCode, Long> = emptyMap(),
	authorCounts: Map<ActionAuthor, Long> = emptyMap(),
	emittedByActionType: Map<String, Long> = emptyMap(),
	correctAt1: Double? = null,
	model: String = "",
	seed: Long? = null,
	temperature: Double = 0.0,
	inferenceTimeoutSeconds: Long = KoogAgentPlanAdapter.DEFAULT_TIMEOUT_SECONDS,
	promptVariant: String = RunParameters.DEFAULT_PROMPT_VARIANT,
	circuitBreakerFailureThreshold: Int = LlmCircuitBreaker.DEFAULT_FAILURE_THRESHOLD,
	circuitBreakerCooldownSeconds: Double = LlmCircuitBreaker.DEFAULT_COOLDOWN_SECONDS,
	railwayOutcome: RailwayOutcome = RailwayOutcome.UNMEASURED,
	ruleFallbackTicks: Long = 0L,
	loggedFatalSimExceptionCount: Long? = null,
	loggedFatalSimExceptionFirstMessage: String? = null
): DispatcherRunSnapshot {
	val outcomes = TickOutcome.entries.associate { it.name to 0L }.toMutableMap()
	outcomes[TickOutcome.LLM_ACTIONS.name] = 1L
	outcomes[TickOutcome.RULE_FALLBACK.name] = ruleFallbackTicks

	return DispatcherRunSnapshot(
		runId = runId,
		arm = arm,
		params =
			RunParameters(
				tickPeriodMs = 500L,
				historyN = 10,
				temperature = temperature,
				maxActionsPerTick = 3,
				model = model,
				seed = seed,
				inferenceTimeoutSeconds = inferenceTimeoutSeconds,
				promptVariant = promptVariant,
				circuitBreakerFailureThreshold = circuitBreakerFailureThreshold,
				circuitBreakerCooldownSeconds = circuitBreakerCooldownSeconds
			),
		totalTicks = 1L + ruleFallbackTicks,
		ticksByOutcome = outcomes,
		timeoutNoOpByCause = TimeoutNoOpCause.entries.associate { it.name to 0L },
		llmSuccessRate = llmSuccessRate,
		actionableTickRate = actionableTickRate,
		noOpRate = noOpRate,
		invalidOutputRate = invalidOutputRate,
		repairSuccessRate = repairSuccessRate,
		emittedByActionType = emittedByActionType,
		rejectionsByCode = rejections.mapKeys { it.key.name },
		applyFailuresByCode = applyFailures.mapKeys { it.key.name },
		validAt1 = 1.0,
		correctAt1 = correctAt1,
		oracleAgreementAt1 = null,
		latencyP50Ms = latencyP50Ms,
		latencyP95Ms = latencyP95Ms,
		latencyMaxMs = latencyMaxMs,
		actionsByAuthor = authorCounts.mapKeys { it.key.name },
		unattributedApplies = 0L,
		terminalFallbackEngaged = fallback,
		terminalFallbackTickIndex = if (fallback) 5L else null,
		c7Clean = c7Clean,
		completedNaturally = completedNaturally,
		endCause = if (completedNaturally) RunEndCause.NATURAL_COMPLETION else RunEndCause.TERMINATED_EARLY,
		railwayOutcome = railwayOutcome,
		loggedFatalSimExceptionCount = loggedFatalSimExceptionCount,
		loggedFatalSimExceptionFirstMessage = loggedFatalSimExceptionFirstMessage
	)
}
