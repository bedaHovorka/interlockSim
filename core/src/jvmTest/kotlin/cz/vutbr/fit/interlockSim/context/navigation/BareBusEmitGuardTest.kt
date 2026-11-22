/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.util.Util
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/**
 * The guarded emit of each block and reservation event site (Issue #1361), on a bare kDisco bus: nothing
 * wraps the listener, so a throwing raw subscriber reaches the emit itself. A plain failure no longer
 * leaves the emitting call; a cancellation still does.
 *
 * @since Issue #1361
 */
@DisplayName("Guarded emit at the block and reservation event sites (Issue #1361)")
@Timeout(30, unit = TimeUnit.SECONDS)
class BareBusEmitGuardTest : EmitSiteScenarios() {
	/** A raw subscriber on the bare bus that throws a plain failure on [payload]; counts its calls. */
	private class PlainThrower(
		private val payload: KClass<*>
	) : (SimulationEvent) -> Unit {
		var calls = 0

		override fun invoke(event: SimulationEvent) {
			if (payload.isInstance((event as? SimulationEvent.Custom)?.payload)) {
				calls++
				error("raw subscriber failure injected by the test")
			}
		}
	}

	private fun throwPlainOnBareBus(payload: KClass<*>): PlainThrower = PlainThrower(payload).also { busListeners += it }

	private fun cancelOnBareBus(payload: KClass<*>) {
		busListeners += { event ->
			if (payload.isInstance(payloadOf(event))) throw CancellationException("injected by the test")
		}
	}

	@Test
	fun `BlockReserved - a plain failure of a raw subscriber does not fail the reservation`() {
		val thrower = throwPlainOnBareBus(BlockEvent.BlockReserved::class)
		var result: PathReservationService.ReservationResult? = null

		atSimTimes(0.0 to { result = service.reservePath(TRAIN, findSemaphoreByName("zA"), findSemaphoreByName("doB1")) })

		assertThat(thrower.calls, "the subscriber threw").isGreaterThan(0)
		assertThat(result).isNotNull().isInstanceOf(PathReservationService.ReservationResult.Success::class)
	}

	@ParameterizedTest(name = "a raw subscriber throwing on {0}")
	@ValueSource(strings = ["ReservationConflictDetected", "ConflictDetectedEvent"])
	fun `rollback - a plain failure of a raw subscriber does not fail the reservation`(payload: String) {
		val throwOn = conflictPayload(payload)
		val thrower = throwPlainOnBareBus(throwOn)
		val scenario = registryConflictScenario()
		var result: PathReservationService.ReservationResult? = null

		atSimTimes(0.0 to { result = scenario() })

		assertThat(thrower.calls, "the subscriber threw").isEqualTo(1)
		assertThat(result).isNotNull().isInstanceOf(PathReservationService.ReservationResult.Conflict::class)
	}

	@Test
	fun `contention tracker ConflictDetectedEvent - a plain failure of a raw subscriber does not fail the request`() {
		val thrower = throwPlainOnBareBus(ConflictDetectedEvent::class)
		var result: PathReservationService.ReservationResult? = null

		atSimTimes(0.0 to { result = contentionScenario() })

		assertThat(thrower.calls, "the subscriber threw").isEqualTo(1)
		assertThat(result).isNotNull().isInstanceOf(PathReservationService.ReservationResult.AllPathsBlocked::class)
	}

	@Test
	fun `SwitchClaimsReleased - a plain failure of a raw subscriber does not fail the teardown`() {
		val thrower = throwPlainOnBareBus(SwitchClaimsReleased::class)
		var blocks = 0 to -1

		atSimTimes(0.0 to { blocks = unregisterScenario() })

		assertThat(thrower.calls, "the subscriber threw").isEqualTo(1)
		assertThat(blocks.second, "blocks released = blocks reserved").isEqualTo(blocks.first)
		assertThat(registry.getSwitches(TRAIN), "switch claims left").isEqualTo(emptyList())
	}

	@Test
	fun `SwitchClaimsReleased - a cancellation leaves the teardown`() {
		cancelOnBareBus(SwitchClaimsReleased::class)
		val caught = mutableListOf<Throwable?>()

		atSimTimes(0.0 to { caught += runCatching { unregisterScenario() }.exceptionOrNull() })

		Util.assertInstanceOf<CancellationException>(caught.single() ?: error("the teardown swallowed the cancellation"))
	}

	@Test
	fun `BlockReleased of a whole-route teardown - a cancellation leaves the teardown`() {
		cancelOnBareBus(BlockEvent.BlockReleased::class)
		val caught = mutableListOf<Throwable?>()

		atSimTimes(0.0 to { caught += runCatching { unregisterScenario() }.exceptionOrNull() })

		Util.assertInstanceOf<CancellationException>(caught.single() ?: error("the teardown swallowed the cancellation"))
	}

	@Test
	fun `BlockReleased of the per-block funnel - a plain failure of a raw subscriber does not fail the release`() {
		val thrower = throwPlainOnBareBus(BlockEvent.BlockReleased::class)
		var released = false
		lateinit var block: DynamicTrackBlock

		atSimTimes(
			0.0 to {
				block = reserveTrainZaToDoB1().reservedBlocks.last()
				released = service.releaseBlock(TRAIN, block)
			}
		)

		assertThat(thrower.calls, "the subscriber threw").isEqualTo(1)
		assertThat(released, "the block was released").isTrue()
		assertThat(registry.getOwner(block), "owner of the released block").isNull()
	}

	@Test
	fun `OccupancySet - a plain failure of a raw subscriber does not fail the entry`() {
		val thrower = throwPlainOnBareBus(BlockEvent.OccupancySet::class)
		val occupant = NamedOccupant(TRAIN)
		lateinit var block: DynamicTrackBlock

		atSimTimes(
			0.0 to {
				block = reserveTrainZaToDoB1().reservedBlocks.first()
				block.enter(occupant)
			}
		)

		assertThat(thrower.calls, "the subscriber threw").isEqualTo(1)
		assertThat(block.occupant, "occupant after the entry").isSameInstanceAs(occupant)
	}

	@Test
	fun `OccupancyCleared - a plain failure of a raw subscriber does not fail the exit`() {
		val thrower = throwPlainOnBareBus(BlockEvent.OccupancyCleared::class)
		val occupant = NamedOccupant(TRAIN)
		lateinit var block: DynamicTrackBlock

		atSimTimes(
			0.0 to {
				block = reserveTrainZaToDoB1().reservedBlocks.first()
				block.enter(occupant)
				block.leave(occupant)
			}
		)

		assertThat(thrower.calls, "the subscriber threw").isEqualTo(1)
		assertThat(block.occupant, "occupant after the exit").isNull()
	}

	@Test
	fun `BlockEntryViolation - a plain failure of a raw subscriber leaves the occupancy check to fail the entry`() {
		val thrower = throwPlainOnBareBus(CollisionWarning.BlockEntryViolation::class)
		var failure = ""

		atSimTimes(0.0 to { failure = doubleOccupancyScenario() })

		assertThat(thrower.calls, "the subscriber threw").isEqualTo(1)
		assertThat(failure, "the second entry's failure").isEqualTo(OCCUPANCY_CHECK_FAILURE)
	}
}
