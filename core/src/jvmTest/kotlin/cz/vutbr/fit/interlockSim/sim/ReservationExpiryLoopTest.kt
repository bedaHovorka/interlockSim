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
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.DefaultReservationExpiry
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
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
 * train lost its route.
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
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `a rule-based ShuntingLoop run of 600 s expires nothing`() {
		val context = loadVyhybnaContext(warmUpDynamicWrappers = true)
		val loop = ShuntingLoop(context, endTime = 600L)
		wireSynchronousDispatcher(context, loop)
		context.setMainProcess(loop)

		context.run()

		// Non-vacuous: trains really travelled and left.
		assertThat(loop.getTrainsExited()).isGreaterThan(0)
		assertThat(expiryOf(context).expiredHoldingCount).isEqualTo(0)
		// No train on the track lost its tail either (Issue #1220): every train that stood still
		// waited at a STOP signal holding only the blocks it stands on.
		assertThat(expiryOf(context).expiredTailCount).isEqualTo(0)
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

		context.run()

		assertThat(loop.getTrainsExited()).isEqualTo(3)
		assertThat(expiryOf(context).expiredHoldingCount).isEqualTo(0)
		assertThat(expiryOf(context).expiredTailCount).isEqualTo(0)
	}

	private companion object {
		const val PHANTOM = "phantom"
		const val TRAIN_LENGTH = 20.0
	}
}
