/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.scope.Scope
import org.koin.mp.KoinPlatform.getKoin

private val logger = KotlinLogging.logger {}

/**
 * End-of-run cleanup helpers for LLM dispatcher runs (Issue #1072).
 *
 * Separates two lifetimes that must not be conflated:
 * - **Per-run Koog agent** — closed when a simulation ends so agent/coroutine workers do not
 *   outlive the run. Safe to recreate on the next [KoogAgentPlanAdapter] cycle.
 * - **Shared [OllamaSimpleExecutor]** — Koin singleton, terminal [OllamaSimpleExecutor.close].
 *   Closed only when the process is about to exit (headless end-of-main, GUI window close), never
 *   on a mid-session STOPPED, so a second GUI start in the same JVM can still call
 *   [OllamaSimpleExecutor.getExecutor].
 *
 * @since Issue #1072
 */
object DispatcherRunLifecycle {
	/**
	 * Release the cached Koog dispatch agent for the run that just ended.
	 *
	 * Looks up [MeasuringPlanAdapter] first (GUI/headless AI examples wrap the planner that way),
	 * then a bare [KoogAgentPlanAdapter] if one was declared without the measuring decorator.
	 * No-op when neither is present (rule-based runs).
	 */
	fun releaseKoogAgent(scope: Scope?) {
		if (scope == null) return
		try {
			scope.getOrNull<MeasuringPlanAdapter>()?.releaseAgent()
				?: scope.getOrNull<KoogAgentPlanAdapter>()?.releaseAgent()
		} catch (e: Exception) {
			logger.warn(e) { "Failed to release Koog agent at end of run" }
		}
	}

	/**
	 * Close the shared Ollama-backed prompt executor if one is bound in the global Koin container.
	 *
	 * Idempotent and safe when Koin is not started or the binding is absent. Terminal for the
	 * singleton: a later [OllamaSimpleExecutor.getExecutor] in this same process will fail unless
	 * Koin recreates the binding (it does not — the binding stays closed until [stopKoin]/restart).
	 */
	fun closeSharedOllamaExecutor() {
		try {
			getKoin().getOrNull<OllamaSimpleExecutor>()?.close()
		} catch (e: Exception) {
			logger.debug(e) { "Ollama executor close skipped (Koin not available or already stopped)" }
		}
	}
}
