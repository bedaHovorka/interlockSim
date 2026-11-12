/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.DefaultReservationExpiry
import cz.vutbr.fit.interlockSim.context.navigation.ReservationExpired
import cz.vutbr.fit.interlockSim.context.navigation.onReservationExpired
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndSummary
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The simulation loops' kernel expiry tick (Issue #1174): `ShuntingLoop.iteration()` and
 * `MultiTrainLoop.iteration()` call [cz.vutbr.fit.interlockSim.context.navigation.ReservationExpiry]
 * once per control step, so a stale holding is released even when nobody calls `reservePath` --
 * `:fast-sim`, `multiTrainLoop` and every run without the dispatcher's `OrphanReservationSweeper`.
 *
 * Plus the clean-baseline tripwires: rule-based runs on `vyhybna.xml`, where every granted route is
 * entered and no train stalls inside its route, expire nothing -- neither a whole holding (Issue
 * #1174) nor the tail of a train on the track (Issue #1220). A non-zero count there means a live
 * train lost its route; nor is a [ReservationExpired] event announced (Issue #1243).
 */
@Tag("integration-test")
class ReservationExpiryLoopTest : KoinTestBase() {
	private fun expiryOf(context: DefaultSimulationContext): DefaultReservationExpiry =
		(context.getRoutingServices().getPathReservationService() as DefaultPathReservationService).expiry

	private fun inOut(
		context: DefaultSimulationContext,
		name: String
	): DynamicPathSeparator = context.toDynamic(context.getInOuts().single { it.name == name })

	/** A route granted to a train that never exists, so nothing ever enters or cancels it. */
	private fun grantPhantomRoute(context: DefaultSimulationContext) {
		assertReservationSuccess(
			context.getRoutingServices().getPathReservationService().reservePath(
				PHANTOM,
				inOut(context, "A"),
				inOut(context, "B")
			)
		)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `ShuntingLoop expires a stale holding on its own tick`() {
		// No dispatcher is wired: nothing calls reservePath, so only the loop's tick can expire it.
		assertPhantomRouteExpiresOnTicksOnly { ShuntingLoop(it, endTime = 100L) }
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `MultiTrainLoop expires a stale holding on its own tick`() {
		// No train specs: the loop never calls reservePath itself.
		assertPhantomRouteExpiresOnTicksOnly { MultiTrainLoop(it, endTime = 100L) }
	}

	/** A loop that never asks the kernel for a route: only the loop's own tick can expire the phantom. */
	private fun assertPhantomRouteExpiresOnTicksOnly(makeLoop: (DefaultSimulationContext) -> LoopProcess) {
		val context = loadVyhybnaContext(warmUpDynamicWrappers = true)
		grantPhantomRoute(context)
		context.setMainProcess(makeLoop(context))

		context.run()

		assertThat(context.getRoutingServices().getPathReservationService().getReservedBlocks(PHANTOM)).isEmpty()
		assertThat(expiryOf(context).expiredHoldingCount).isEqualTo(1)
		// Issue #1304: the metrics count the same expiry from its event, not from the counter.
		val summary = context.getMetricsServices().getMetricsCollectionService().runEndSummary()
		assertThat(summary.expiredHoldingCount, name = "expiries the metrics counted").isEqualTo(1)
		assertThat(summary.expiredTailCount, name = "tail expiries the metrics counted").isEqualTo(0)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `a raw subscriber throwing on ReservationExpired does not change a ShuntingLoop run`() {
		// Issue #1310: the counters and the run's outcome are the evidence, never a second
		// subscriber -- a raw subscriber that throws ends the bus's delivery to the ones after it.
		val baseline = phantomExpiryRun(throwingRawSubscriber = false)
		val withThrowing = phantomExpiryRun(throwingRawSubscriber = true)

		assertThat(withThrowing.subscriberCalls, name = "calls of the throwing subscriber").isEqualTo(1)
		assertThat(withThrowing.expiredHoldingCount, name = "expiries counted").isEqualTo(1)
		assertThat(withThrowing.phantomBlocksLeft, name = "phantom blocks still reserved").isEqualTo(0)
		assertThat(withThrowing.copy(subscriberCalls = 0), name = "run outcome").isEqualTo(baseline)
	}

	/** What [phantomExpiryRun] observed: the expiry counters, the phantom's holding and the run-end record. */
	private data class PhantomExpiryOutcome(
		val subscriberCalls: Int,
		val expiredHoldingCount: Int,
		val expiredTailCount: Int,
		val phantomBlocksLeft: Int,
		val summary: RunEndSummary
	)

	/**
	 * The phantom-route `ShuntingLoop` run of [assertPhantomRouteExpiresOnTicksOnly], optionally with a
	 * raw `onSimulationEvent` subscriber that throws on every [ReservationExpired].
	 */
	private fun phantomExpiryRun(throwingRawSubscriber: Boolean): PhantomExpiryOutcome {
		val context = loadVyhybnaContext(warmUpDynamicWrappers = true)
		grantPhantomRoute(context)
		context.setMainProcess(ShuntingLoop(context, endTime = 100L))
		var calls = 0
		if (throwingRawSubscriber) {
			context.onSimulationEvent { event ->
				if ((event as? SimulationEvent.Custom)?.payload is ReservationExpired) {
					calls++
					error("raw subscriber failure injected by the test")
				}
			}
		}

		context.run()

		val expiry = expiryOf(context)
		val reservations = context.getRoutingServices().getPathReservationService()
		return PhantomExpiryOutcome(
			subscriberCalls = calls,
			expiredHoldingCount = expiry.expiredHoldingCount,
			expiredTailCount = expiry.expiredTailCount,
			phantomBlocksLeft = reservations.getReservedBlocks(PHANTOM).size,
			// The wall-clock performance row differs run to run. The metrics count expiries from the event
			// through their own subscriber, registered after the throwing one, so they miss it: those two
			// counts are left out, and the expiry's own counters above stand for them.
			summary =
				context
					.getMetricsServices()
					.getMetricsCollectionService()
					.runEndSummary()
					.copy(performance = null, expiredHoldingCount = 0, expiredTailCount = 0)
		)
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `a rule-based ShuntingLoop run of 600 s expires nothing`() {
		val context = loadVyhybnaContext(warmUpDynamicWrappers = true)
		val loop = ShuntingLoop(context, endTime = 600L)
		wireSynchronousDispatcher(context, loop)
		context.setMainProcess(loop)
		val events = mutableListOf<ReservationExpired>()
		context.onReservationExpired { events += it }

		context.run()

		// Non-vacuous: trains really travelled and left.
		assertThat(loop.getTrainsExited()).isGreaterThan(0)
		assertThat(expiryOf(context).expiredHoldingCount).isEqualTo(0)
		// No train on the track lost its tail either (Issue #1220): every train that stood still
		// waited at a STOP signal holding only the blocks it stands on.
		assertThat(expiryOf(context).expiredTailCount).isEqualTo(0)
		// ... so no ReservationExpired event either (Issue #1243): the event's clean-baseline tripwire.
		assertThat(events).isEmpty()
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `a MultiTrainLoop run of three trains expires nothing`() {
		val context = loadVyhybnaContext(warmUpDynamicWrappers = true)
		val loop =
			MultiTrainLoop(
				context,
				endTime = 400L,
				trainSpecs =
					listOf(0.0, 2.0, 4.0).map { inTime ->
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = inTime, length = TRAIN_LENGTH)
					}
			)
		context.setMainProcess(loop)
		val events = mutableListOf<ReservationExpired>()
		context.onReservationExpired { events += it }

		context.run()

		assertThat(loop.getTrainsExited()).isEqualTo(3)
		assertThat(expiryOf(context).expiredHoldingCount).isEqualTo(0)
		assertThat(expiryOf(context).expiredTailCount).isEqualTo(0)
		assertThat(events).isEmpty()
	}

	private companion object {
		const val PHANTOM = "phantom"
		const val TRAIN_LENGTH = 20.0
	}
}
