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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests of [RouteSignalConfigurator] (Issue #1169), the route signal configuration moved
 * out of [DefaultPathReservationService], on `vyhybna.xml`. The main line runs
 * `A - zA - vA - doA1 - doB1 - vB - zB - B`; `zA`/`doB1` face an `A -> B` movement and
 * `doA1`/`zB` face the opposite one. No reservation runs, so every aspect seen here is the
 * configurator's own.
 */
class RouteSignalConfiguratorTest : PathReservationServiceTestBase() {
	private val ledger = ClearedSignalLedger()
	private lateinit var configurator: RouteSignalConfigurator

	/** The `A -> B` main line in travel order. */
	private lateinit var aToB: List<DynamicTrackBlock>

	@BeforeEach
	fun setUpConfigurator() {
		configurator = RouteSignalConfigurator(environment, ledger)
		aToB =
			listOf(
				blockBetween("A", "zA"),
				blockBetween("zA", "vA"),
				blockBetween("vA", "doA1"),
				blockBetween("doA1", "doB1"),
				blockBetween("doB1", "vB"),
				blockBetween("vB", "zB"),
				blockBetween("zB", "B")
			)
	}

	@Test
	fun `configureStartSignal clears and records a semaphore START facing the travel direction`() {
		val doA1 = findSemaphoreByName("doA1")
		val towardA = blockBetween("vA", "doA1")

		val result = configurator.configureStartSignal("t1", doA1, listOf(towardA), towardA)

		assertThat(result).isEqualTo(StartSignalResult.Configured)
		assertThat(doA1.signal.isAllowing()).isTrue()
		assertThat(ledger.snapshotClearedSemaphores("t1").toList()).containsExactly(doA1)
	}

	@Test
	fun `configureStartSignal rejects a rear-facing semaphore START without touching it`() {
		val doA1 = findSemaphoreByName("doA1")
		val towardB = blockBetween("doA1", "doB1")

		val result = configurator.configureStartSignal("t1", doA1, listOf(towardB), towardB)

		assertThat(result).isEqualTo(StartSignalResult.RejectedG4)
		assertThat(doA1.signal).isEqualTo(Signal.STOP)
		assertThat(ledger.snapshotClearedSemaphores("t1")).isEmpty()
	}

	@Test
	fun `configureStartSignal clears an InOut START's inSemaphore`() {
		val inOutA = inOutNamed("A") as DynamicInOut

		val result = configurator.configureStartSignal("t1", inOutA, aToB, aToB.first())

		assertThat(result).isEqualTo(StartSignalResult.Configured)
		assertThat(inOutA.inSemaphore.signal.isAllowing()).isTrue()
		assertThat(ledger.snapshotClearedSemaphores("t1").toList()).containsExactly(inOutA.inSemaphore)
	}

	@Test
	fun `configureStartSignal fails for a START that carries no signal`() {
		val vA = simulationContext.cellsOfType<DynamicRailSwitch>().single { it.name == "vA" }

		val result = configurator.configureStartSignal("t1", vA, aToB.drop(2), aToB[2])

		assertThat(result).isEqualTo(StartSignalResult.ConfigFailed)
	}

	@Test
	fun `configureIntermediateSemaphores clears only the facing boundaries, in route order`() {
		configurator.configureIntermediateSemaphores("t1", aToB, aToB.toSet())

		assertThat(ledger.snapshotClearedSemaphores("t1").map { it.name }).containsExactly("zA", "doB1")
		assertThat(findSemaphoreByName("doA1").signal).isEqualTo(Signal.STOP)
		assertThat(findSemaphoreByName("zB").signal).isEqualTo(Signal.STOP)
	}

	@Test
	fun `configureIntermediateSemaphores skips a boundary into an already-owned block`() {
		val forward = aToB.drop(4).toSet()

		configurator.configureIntermediateSemaphores("t1", aToB, forward)

		assertThat(ledger.snapshotClearedSemaphores("t1").map { it.name }).containsExactly("doB1")
		assertThat(findSemaphoreByName("zA").signal).isEqualTo(Signal.STOP)
	}

	@Test
	fun `configureBypassStartSignal relights a facing semaphore START and leaves a rear-facing one`() {
		val doA1 = findSemaphoreByName("doA1")

		configurator.configureBypassStartSignal("t1", doA1, listOf(blockBetween("doA1", "doB1")))
		assertThat(doA1.signal).isEqualTo(Signal.STOP)

		configurator.configureBypassStartSignal("t1", doA1, listOf(blockBetween("vA", "doA1")))
		assertThat(doA1.signal.isAllowing()).isTrue()
		assertThat(ledger.snapshotClearedSemaphores("t1").toList()).containsExactly(doA1)
	}

	@Test
	fun `configureBypassStartSignal ignores an InOut START and an empty block list`() {
		configurator.configureBypassStartSignal("t1", inOutNamed("A"), aToB)
		configurator.configureBypassStartSignal("t1", findSemaphoreByName("doA1"), emptyList())

		assertThat(ledger.snapshotClearedSemaphores("t1")).isEmpty()
	}

	@Test
	fun `configureAlreadyOwnedStartSignal skips a rear-facing semaphore and clears a facing one`() {
		val doA1 = findSemaphoreByName("doA1")

		configurator.configureAlreadyOwnedStartSignal("t1", doA1, listOf(blockBetween("doA1", "doB1")))
		assertThat(doA1.signal).isEqualTo(Signal.STOP)

		configurator.configureAlreadyOwnedStartSignal("t1", doA1, listOf(blockBetween("vA", "doA1")))
		assertThat(doA1.signal.isAllowing()).isTrue()
		assertThat(ledger.snapshotClearedSemaphores("t1").toList()).containsExactly(doA1)
	}

	@Test
	fun `configureAlreadyOwnedStartSignal clears an InOut START's inSemaphore`() {
		val inOutA = inOutNamed("A") as DynamicInOut

		configurator.configureAlreadyOwnedStartSignal("t1", inOutA, aToB)

		assertThat(inOutA.inSemaphore.signal.isAllowing()).isTrue()
		assertThat(ledger.snapshotClearedSemaphores("t1").toList()).containsExactly(inOutA.inSemaphore)
	}
}
