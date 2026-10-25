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
import assertk.assertions.containsExactly
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Pins the ORDER of signal operations [DefaultPathReservationService] performs for one
 * `vyhybna.xml` route, so the signal-configuration extraction (Issue #1169, SP12.3) can prove it
 * moved code without reordering a single signal write.
 *
 * Two observation channels feed one ordered log:
 * - `configure <name>`: every [SimulationContext.configureSemaphoreSignal] call, recorded by a
 *   delegating environment before it forwards the call (the semaphore START and intermediate
 *   semaphores go through it);
 * - `<name>: <old> -> <new>`: every aspect change of a grid semaphore or of an InOut's embedded
 *   semaphores, recorded by a property-change listener (this also sees the InOut START's
 *   `inSemaphore.setUpSpeed` and every reset to STOP on release).
 *
 * Written against the code before the move and kept unchanged after it.
 */
@Tag("integration-test")
class RouteSignalOperationOrderTest : PathReservationServiceTestBase() {
	private val log = mutableListOf<String>()
	private lateinit var orderedService: DefaultPathReservationService

	/** Records each `configureSemaphoreSignal` call into [log], then delegates to [real]. */
	private class RecordingEnvironment(
		private val real: SimulationContext,
		private val log: MutableList<String>
	) : SimulationContext by real {
		override fun configureSemaphoreSignal(
			semaphore: DynamicRailSemaphore,
			firstBlock: DynamicTrackBlock,
			allowedSpeed: Double?
		) {
			log += "configure ${semaphore.name}"
			real.configureSemaphoreSignal(semaphore, firstBlock, allowedSpeed)
		}
	}

	@BeforeEach
	fun setUpOrderedService() {
		orderedService =
			DefaultPathReservationService(
				navigator,
				RecordingEnvironment(simulationContext, log),
				registry,
				pathInfoBuilder,
				routeFinder
			)
		// An InOut's embedded semaphores carry no name of their own; label them after the InOut.
		val watched =
			simulationContext.cellsOfType<DynamicRailSemaphore>().associateWith { it.name } +
				simulationContext
					.getInOuts()
					.map { simulationContext.toDynamic(it) }
					.filterIsInstance<DynamicInOut>()
					.flatMap { listOf(it.inSemaphore to "${it.name}.in", it.outSemaphore to "${it.name}.out") }
		watched.forEach { (semaphore, label) ->
			semaphore.addPropertyChangeListener(
				ContextPropertyChangeListener { event ->
					log += "$label: ${event.oldValue} -> ${event.newValue}"
				}
			)
		}
	}

	@Test
	fun `InOut A to InOut B route clears then resets its signals in a fixed order`() {
		val result = orderedService.reservePath("orderTrain", inOutNamed("A"), inOutNamed("B"))
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		log += "-- release --"
		orderedService.releasePath("orderTrain")

		assertThat(log).containsExactly(*EXPECTED_A_TO_B.toTypedArray())
	}

	@Test
	fun `semaphore doA1 to InOut A route clears then resets its signals in a fixed order`() {
		val result =
			orderedService.reservePath("orderTrain", findSemaphoreByName("doA1"), inOutNamed("A"), maxDepth = 4)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		log += "-- release --"
		orderedService.releasePath("orderTrain")

		assertThat(log).containsExactly(*EXPECTED_DOA1_TO_A.toTypedArray())
	}

	private companion object {
		/**
		 * A -> B: the InOut START's `inSemaphore` is cleared first (Step 2g), then the facing
		 * intermediate semaphores in route order (Step 2h); the rear-passed `doA1` and `zB` are
		 * never touched. Release resets them in the order they were recorded.
		 */
		val EXPECTED_A_TO_B: List<String> =
			listOf(
				"A.in: STOP -> S80",
				"configure zA",
				"zA: STOP -> S80",
				"configure doB1",
				"doB1: STOP -> S80",
				"-- release --",
				"A.in: S80 -> STOP",
				"zA: S80 -> STOP",
				"doB1: S80 -> STOP"
			)

		/** doA1 -> A: only the semaphore START is configured; the rear-passed `zA` stays at STOP. */
		val EXPECTED_DOA1_TO_A: List<String> =
			listOf(
				"configure doA1",
				"doA1: STOP -> S80",
				"-- release --",
				"doA1: S80 -> STOP"
			)
	}
}
