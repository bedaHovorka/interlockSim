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
import assertk.assertions.isNotEmpty
import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import kotlin.reflect.KClass

/**
 * The scenarios of the emit-site guard tests (Issue #1361), on `vyhybna.xml`: each reaches one emit
 * site of the block and reservation events. [EmitSiteGuardTest] runs them in a context run (the delivery
 * guard), [BareBusEmitGuardTest] on a bare kDisco bus (the guarded emit of each site).
 *
 * @since Issue #1361
 */
abstract class EmitSiteScenarios : ReservationExpiryTestBase() {
	private var mockedBlock: DynamicTrackBlock? = null

	@AfterEach
	fun unmockBlock() {
		mockedBlock?.let { unmockkObject(it) }
	}

	/** Minimal [TrackOccupant] carrying only a name. */
	protected class NamedOccupant(
		override val name: String
	) : TrackOccupant {
		override fun distanceToSemaphore(): Double = 0.0

		override fun nextSemaphore(): OrientedPathSeparator? = null
	}

	/** The payload of [event] when it is a `SimulationEvent.Custom`, else `null`. */
	protected fun payloadOf(event: SimulationEvent): Any? = (event as? SimulationEvent.Custom)?.payload

	/** The payload class a parameterized test names by its simple name. */
	protected fun conflictPayload(name: String): KClass<*> =
		if (name == "ConflictDetectedEvent") ConflictDetectedEvent::class else BlockEvent.ReservationConflictDetected::class

	protected fun reserveTrainZaToDoB1(): PathReservationService.ReservationResult.Success =
		assertReservationSuccess(service.reservePath(TRAIN, findSemaphoreByName("zA"), findSemaphoreByName("doB1")))

	/**
	 * The registry-conflict rollback of [CandidateCommitter] (the shape of `ReservePathToctouRollbackTest`):
	 * another train registers the candidate's last block inside Step 2c, so Step 2d's `registerAtomic`
	 * conflicts and both conflict events go out. Returns the reservation result.
	 */
	protected fun registryConflictScenario(): () -> PathReservationService.ReservationResult {
		val zA = findSemaphoreByName("zA")
		val inOutB = inOutNamed("B")
		val candidates = navigator.findAllTopologicalPaths(zA, inOutB, DEFAULT_MAX_PATH_DEPTH)
		val forwardBlocks = extractUniqueBlocks(candidates.first())
		val trigger = forwardBlocks.first()
		val contested = forwardBlocks.last()
		var fired = false
		mockkObject(trigger)
		mockedBlock = trigger
		every { trigger.setUpPath(any(), TRAIN) } answers {
			callOriginal()
			if (!fired) {
				fired = true
				registry.registerAtomic(OTHER, listOf(contested))
			}
		}
		return { service.reservePath(TRAIN, zA, inOutB) }
	}

	/**
	 * Reserve zA -> doB1 (it crosses switches), then tear the train down; returns the reserved and the
	 * released blocks.
	 */
	protected fun unregisterScenario(): Pair<Int, Int> {
		val reserved = reserveTrainZaToDoB1().reservedBlocks.size
		assertThat(registry.getSwitches(TRAIN), "switches the route claims").isNotEmpty()
		return reserved to service.unregister(TRAIN).size
	}

	/** Occupy a block for [TRAIN], then let [OTHER] enter it as well; returns the second entry's failure. */
	protected fun doubleOccupancyScenario(): String {
		val block = reserveTrainZaToDoB1().reservedBlocks.first()
		block.enter(NamedOccupant(TRAIN))
		val second = runCatching { block.enter(NamedOccupant(OTHER)) }.exceptionOrNull()
		return second?.let { it::class.simpleName } ?: "no failure"
	}

	/**
	 * [OTHER] asks for the route [TRAIN] holds, so the contention tracker emits its one-shot
	 * [ConflictDetectedEvent]; the contention is still open when the run ends, so the run-end flush delivers a
	 * [BlockEvent.ReservationConflictDetected] to the typed block listeners.
	 */
	protected fun contentionScenario(): PathReservationService.ReservationResult {
		reserveTrainZaToDoB1()
		return service.reservePath(OTHER, findSemaphoreByName("zA"), findSemaphoreByName("doB1"))
	}

	protected companion object {
		const val TRAIN = "t1"

		/** What the occupancy check of `DynamicTrackBlock.enter` throws on a second occupant. */
		val OCCUPANCY_CHECK_FAILURE: String? = SimulationException::class.simpleName
		const val OTHER = "other"
	}
}
