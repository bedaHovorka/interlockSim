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
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.koin.test.inject

/**
 * G8 (Issue #1064): a route must END at a signal that faces the train.
 *
 * `vyhybna.xml` is a passing loop: `A - zA - vA - {doA1 k1 doB1 | doA2 k2 doB2} - vB - zB - B`.
 * `zA`, `doB1` and `doB2` face east (A -> B trains), `zB`, `doA1` and `doA2` face west. A route
 * that ends at a signal facing the other way gives the train nothing it can use: navigation cannot
 * build a leg past the last facing signal, so the train stops there holding track it cannot leave
 * and waits for an extension (Issue #1031, Issue #1060). The rule-based path never asks for such a
 * route (`findNextSemaphoresVia` skips backward-facing signals); only a free-form
 * `request_route(from, to)` does, so the interlocking must refuse it — permanently, before it
 * touches any block, switch or signal, and naming the ends that would work.
 *
 * G4 (Issue #903) is the START-side twin of this rule; see `PathReservationServiceTest.StartDirectionTests`.
 */
@Tag("integration-test")
@DisplayName("G8 — a route must end at a signal facing the direction of travel")
class RouteEndDirectionTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var registry: PathReservationRegistry
	private lateinit var service: PathReservationService

	private val trainId = "endTrain"

	@BeforeEach
	fun setUp() {
		simulationContext = TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)
		service = simulationContext.getRoutingServices().getPathReservationService()
		registry = simulationContext.scope.get()
	}

	@ParameterizedTest(name = "{0} -> {1} is refused")
	@CsvSource(
		"A, doA1",
		"A, doA2",
		"zA, doA1",
		"zA, doA2",
		"B, doB1",
		"B, doB2",
		"zB, doB1",
		"doA1, zA",
		"doB1, zB"
	)
	@DisplayName("a route whose end faces away from the train is refused as geometrically impossible")
	fun rearFacingEndIsRefused(
		from: String,
		to: String
	) {
		val result = service.reservePath(trainId, separatorNamed(from), separatorNamed(to))

		assertThat(result, "result of $from -> $to")
			.isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()
		val reason = (result as PathReservationService.ReservationResult.GeometricallyImpossible).reason
		assertThat(reason, "denial reason").contains("faces away from the direction of travel")
		assertThat(reason, "denial reason").contains("'$to'")
		assertThat(registry.getBlocks(trainId), "blocks held after the refusal").isEmpty()
		allBlocks().forEach { block ->
			assertThat(block.getState(), "state of $block after the refusal").isEqualTo(TrackFacility.State.FREE)
		}
	}

	@Test
	@DisplayName("the refusal names the ends a route from that origin may use")
	fun refusalNamesTheLegalEnds() {
		val result = service.reservePath(trainId, separatorNamed("zA"), separatorNamed("doA1"))

		val reason = (result as PathReservationService.ReservationResult.GeometricallyImpossible).reason
		assertThat(reason, "denial reason").contains("doB1")
		assertThat(reason, "denial reason").contains("doB2")
	}

	@Test
	@DisplayName("a refused route leaves every signal as it was")
	fun refusalClearsNoSignal() {
		val signals = allSemaphores()
		val before = signals.associateWith { it.signal }

		service.reservePath(trainId, separatorNamed("A"), separatorNamed("doA2"))

		signals.forEach { semaphore ->
			assertThat(semaphore.signal, "aspect of ${semaphore.name} after the refusal").isEqualTo(before[semaphore])
		}
	}

	/**
	 * Issue #1065 is the reason this matters: a candidate that is refused only after Step 2f has
	 * already thrown its switches can leave a switch in the wrong position. G8 must refuse before any
	 * switch is touched.
	 *
	 * `maxDepth = 3` keeps the search to the direct `zA -> vA -> doA1` candidate. Without it the
	 * search also finds the loop-around candidate that reaches `doA1` from the east through `k1`;
	 * that one faces the train at its end, so G8 lets it through, and the #742 switch guard rejects
	 * it only after `vA` was thrown — the #1065 residue, which is not what this test is about.
	 */
	@Test
	@DisplayName("a refused route throws and locks no switch")
	fun refusalTouchesNoSwitch() {
		val switches = allSwitches()
		val confBefore = switches.associateWith { it.conf }

		service.reservePath(trainId, separatorNamed("zA"), separatorNamed("doA1"), maxDepth = 3)

		switches.forEach { switch ->
			assertThat(switch.conf, "position of ${switch.name} after the refusal").isEqualTo(confBefore[switch])
			assertThat(switch.locked, "lock of ${switch.name} after the refusal").isFalse()
		}
	}

	@ParameterizedTest(name = "{0} -> {1} is granted")
	@CsvSource(
		"A, B",
		"A, zA",
		"zA, doB1",
		"zA, doB2",
		"zA, B",
		"doA1, A",
		"zB, doA1",
		"zB, doA2",
		"doB1, B",
		"B, zB",
		"B, A"
	)
	@DisplayName("a route that ends at a facing signal or an InOut is still granted")
	fun facingEndIsGranted(
		from: String,
		to: String
	) {
		val result = service.reservePath(trainId, separatorNamed(from), separatorNamed(to))

		assertThat(result, "result of $from -> $to").isInstanceOf<PathReservationService.ReservationResult.Success>()
	}

	/**
	 * The already-owned early return must not let a rear-facing end through either: a train that
	 * holds `A -> B` re-requesting the sub-route that stops at the west-facing signal of its own loop
	 * track would otherwise get `Success` for a route it cannot use.
	 */
	@Test
	@DisplayName("re-requesting a held sub-route that ends at a rear-facing signal is refused too")
	fun heldSubRouteWithRearFacingEndIsRefused() {
		assertThat(service.reservePath(trainId, separatorNamed("A"), separatorNamed("B")))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val heldBefore = registry.getBlocks(trainId)
		val ownTrackEnd = if (heldBefore.contains(blockBetween("vA", "doA1"))) "doA1" else "doA2"

		val result = service.reservePath(trainId, separatorNamed("zA"), separatorNamed(ownTrackEnd))

		assertThat(result, "result of zA -> $ownTrackEnd for a train holding A -> B")
			.isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()
		assertThat(registry.getBlocks(trainId), "blocks held after the refusal").isEqualTo(heldBefore)
	}

	private fun separatorNamed(name: String): DynamicPathSeparator =
		simulationContext.getInOuts().singleOrNull { it.name == name }
			?: allSemaphores().single { it.name == name }

	private fun allSemaphores(): List<DynamicRailSemaphore> = allCells().filterIsInstance<DynamicRailSemaphore>()

	private fun allSwitches(): List<DynamicRailSwitch> = allCells().filterIsInstance<DynamicRailSwitch>()

	private fun allCells(): List<Any> {
		val grid = simulationContext.getRailWayNetGrid()
		return (0 until grid.cols).flatMap { x -> (0 until grid.rows).mapNotNull { y -> grid[Point(x, y)] } }
	}

	private fun allBlocks(): List<DynamicTrackBlock> =
		simulationContext
			.getGraph()
			.values()
			.filterIsInstance<DynamicTrackBlock>()
			.distinct()

	private fun nameOf(separator: PathSeparator): String? =
		when (separator) {
			is DynamicRailSemaphore -> separator.name
			is DynamicRailSwitch -> separator.name
			is DynamicInOut -> separator.name
			else -> null
		}

	private fun blockBetween(
		first: String,
		second: String
	): DynamicTrackBlock =
		allBlocks().first { block -> block.ends().mapNotNull { nameOf(it) }.toSet() == setOf(first, second) }
}
