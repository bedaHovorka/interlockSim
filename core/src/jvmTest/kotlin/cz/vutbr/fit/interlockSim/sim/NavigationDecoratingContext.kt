/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Test utility: a simulation context with a decorated navigation service
 * 2026
 */
package cz.vutbr.fit.interlockSim.sim

import cz.ksimulantenbande.kdisco.Condition
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.context.navigation.RoutingServices
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator

/**
 * A [SimulationContext] that serves [nav] in place of the real [TrainNavigationService] and
 * optionally reports every `errorStop` to [onErrorStop].
 *
 * Hand it to `ShuntingLoop` and `wireSynchronousDispatcher`, and keep driving the *real* context:
 *
 * ```kotlin
 * val decorated = NavigationDecoratingContext(context, myNav) { captured.add(it) }
 * val loop = ShuntingLoop(decorated, END_TIME)
 * wireSynchronousDispatcher(decorated, loop)
 * context.setMainProcess(loop)
 * context.run()
 * ```
 *
 * ## Why this exists rather than MockK `spyk`
 *
 * A `spyk(context)` with `every { spy.getRoutingServices() } returns …` reads the same and was the
 * original pattern here, but MockK records **every** call made on the spy so that `verify` can
 * inspect it later. A train suspended on `waitUntil(createPathAvailableCondition(...))` calls
 * `getRoutingServices()` after every discrete event and every integration step, so a scenario in
 * which a train stays stalled for a few hundred simulated seconds accumulates millions of recorded
 * calls: measured on Issue #943's fixture, a 512 MB test worker died with `OutOfMemoryError` after
 * several minutes, before the behaviour under test could occur at all. This wrapper records
 * nothing, so run cost stays flat however long a train waits.
 *
 * ## Why [createPathAvailableCondition] is overridden by hand
 *
 * It is a *default* interface method whose body calls `getRoutingServices()`. Kotlin's
 * `by delegate` forwards the call itself, but the default body still resolves
 * `getRoutingServices()` against the delegate's own `this` — so without this override a waiting
 * train would silently consult the real, un-decorated navigation service. (MockK resolves default
 * methods against the spy, which is why the `spyk` form did not need this.) The same override
 * exists for the same reason on `dispatcher-agent`'s `RouteHidingContext`.
 *
 * @param delegate the real context; everything not overridden here goes straight to it
 * @param nav the navigation service the simulation must see
 * @param onErrorStop called with every `errorStop` reason before it is forwarded, or `null` when
 *   the test does not inspect them
 */
internal class NavigationDecoratingContext(
	private val delegate: SimulationContext,
	nav: TrainNavigationService,
	private val onErrorStop: ((Throwable) -> Unit)? = null
) : SimulationContext by delegate {
	private val routing =
		object : RoutingServices by delegate.getRoutingServices() {
			override fun getTrainNavigationService(): TrainNavigationService = nav
		}

	override fun getRoutingServices(): RoutingServices = routing

	/** Forwarded so the simulation still shuts down, reported so the test can assert the reason. */
	override fun errorStop(error: Throwable) {
		onErrorStop?.invoke(error)
		delegate.errorStop(error)
	}

	override fun createPathAvailableCondition(
		trainId: String,
		separator: PathSeparator
	): Condition =
		Condition {
			routing.getTrainNavigationService().findReservedPathForTrain(trainId, separator) is PathResult.Available
		}
}
