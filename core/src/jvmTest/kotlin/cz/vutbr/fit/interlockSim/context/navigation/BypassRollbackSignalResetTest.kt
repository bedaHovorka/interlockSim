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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotInstanceOf
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Proves the bypass-rollback in
 * [DefaultPathReservationService.reservePathToAnyNextSemaphore] returns the semaphores it cleared
 * to [Signal.STOP] (Issue #847, SP2c.24 code-review follow-up).
 *
 * ## Why this is a separate class from [PathReservationServiceTest]
 *
 * The rollback needs a *reachable alternative route*: `reservePath` must succeed via a path that
 * does not contain the required `next` block. `vyhybna.xml` — which
 * [PathReservationServiceTest] is built around — never produces that shape; a sweep over every
 * (start × adjacent block × blocked/free) combination in it reaches the branch zero times.
 * `parallel-routes.xml` does, because its two genuinely parallel routes between `swA` and `swB`
 * give `reservePath` somewhere else to go when the direct one is taken.
 *
 * ## The defect
 *
 * Every other rollback in the class runs *before* step 2g/2h signal configuration, so a rolled-back
 * candidate has cleared nothing. This one is the exception: it runs after a fully successful
 * `reservePath` has already lit the candidate's signals. Before the fix it released the blocks and
 * left the aspects standing — `sem1` reading S80 over track the interlocking had just returned to
 * FREE, the same evergreen-aspect class of defect #847 fixes on the release paths. That aspect
 * authorises an opposing movement onto track nothing is protecting.
 *
 * @since Issue #847 (SP2c.24 — code-review follow-up)
 */
@Tag("integration-test")
@DisplayName("SP2c.24 — the bypass-rollback resets the signals it cleared (#847)")
class BypassRollbackSignalResetTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var context: DefaultSimulationContext
	private lateinit var service: PathReservationService

	@BeforeEach
	fun setUp() {
		val editing = editingContextFactory.createContext(TestFixtures.loadParallelRoutesXml()) as EditingContext
		context = simulationContextFactory.createContext(editing) as DefaultSimulationContext
		service = context.getRoutingServices().getPathReservationService()
	}

	@Test
	@DisplayName("a candidate rolled back for bypassing the required block leaves no proceed aspect standing")
	fun bypassRollbackReturnsItsSemaphoresToStop() {
		val sem1 = semaphoreNamed("sem1")
		// The block on sem1's far side, towards swB. Reserving it for another train is what forces
		// reservePath to reach its target the other way round the loop -- via sem2 -- producing a
		// Success whose blocks do not contain the `next` this call requires, which is exactly the
		// bypass condition.
		val requiredNext = blockBetween("RailSemaphore:sem1", "RailSwitch:swB")
		requiredNext.setUpPath(sem1, "other-train")

		val result = service.reservePathToAnyNextSemaphore("train1", sem1, requiredNext)

		// The call must fail: every candidate either bypasses the required block (rolled back) or
		// is blocked, and one bypass candidate is permanently geometrically impossible (the
		// sem1 -> swA -> sem2 -> swB -> B path needs swA to join F-to-G, a main-to-branch join
		// that SIMPLE_RIGHT_FALSE cannot make). Which failure class comes back is deliberately not
		// pinned here — this test's concern is the bypass-rollback signal/switch cleanup below,
		// which runs identically regardless. (It is AllPathsBlocked since Issue #937, because not
		// every candidate is geometrically impossible; PathReservationServiceTest owns that rule.)
		assertThat(result)
			.withMessage("a reservation whose every candidate failed must not report success")
			.isNotInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(service.getReservedBlocks("train1"))
			.withMessage("a failed reservation must own no blocks")
			.isEmpty()

		// The regression itself: before the fix sem1 stood at S80 here, authorising a movement onto
		// track that the same call had just released.
		assertThat(sem1.signal)
			.withMessage(
				"sem1 was cleared by the candidate that was then rolled back for bypassing the " +
					"required block; releasing that candidate's track while leaving its aspect at " +
					"proceed authorises a movement the interlocking is no longer protecting"
			).isEqualTo(Signal.STOP)
		assertThat(litSemaphoreNames())
			.withMessage("no reservation survives this call, so no proceed aspect may either")
			.isEmpty()
	}

	@Test
	@DisplayName("the rollback resets only its own candidate's signals, not a live reservation's")
	fun bypassRollbackLeavesAPreExistingReservationLit() {
		// The reason the fix resets the before/after delta rather than the train's whole cleared
		// set: a mid-journey train can hold a live reservation while a further hop is attempted and
		// rolled back. Dropping that reservation's aspect to STOP would strand a train already
		// running against it.
		val entry = inOutNamed("A")
		val firstHop = service.reservePath("train1", entry, semaphoreNamed("sem1"))
		assertThat(firstHop).isInstanceOf<PathReservationService.ReservationResult.Success>()
		firstHop as PathReservationService.ReservationResult.Success
		val litAfterFirstHop = litSemaphoreNames()

		val sem1 = semaphoreNamed("sem1")
		val requiredNext = blockBetween("RailSemaphore:sem1", "RailSwitch:swB")
		requiredNext.setUpPath(sem1, "other-train")

		service.reservePathToAnyNextSemaphore("train1", sem1, requiredNext)

		assertThat(litSemaphoreNames() - litAfterFirstHop.toSet())
			.withMessage("the rolled-back candidate must not leave any new aspect standing")
			.isEmpty()
		assertThat(service.getReservedBlocks("train1").map { it.staticRef.toString() }.toSet())
			.withMessage("the rolled-back candidate must not disturb the train's live reservation")
			.isEqualTo(firstHop.reservedBlocks.map { it.staticRef.toString() }.toSet())
	}

	@Test
	@DisplayName("a rolled-back bypass candidate registers no switches and locks none")
	fun bypassRollbackLocksNoSwitchesWhenNothingSurvives() {
		val registry: PathReservationRegistry = context.scope.get()
		val sem1 = semaphoreNamed("sem1")
		val requiredNext = blockBetween("RailSemaphore:sem1", "RailSwitch:swB")
		requiredNext.setUpPath(sem1, "other-train")

		val result = service.reservePathToAnyNextSemaphore("train1", sem1, requiredNext)

		// The call must fail: every candidate either bypasses the required block (rolled back) or
		// is blocked, and the sem1 -> swA -> sem2 candidate is permanently geometrically impossible
		// (swA must join F-to-G, a main-to-branch join SIMPLE_RIGHT_FALSE cannot make). Which
		// failure class comes back is deliberately not pinned here — this test's concern is the
		// switch cleanup below, which runs identically regardless. (It is AllPathsBlocked since
		// Issue #937; PathReservationServiceTest owns that rule.)
		assertThat(result)
			.withMessage("a reservation whose every candidate failed must not report success")
			.isNotInstanceOf<PathReservationService.ReservationResult.Success>()
		// The transactional rollback extends the block/signal cleanup to switch state: a rejected
		// candidate may not leave switches locked or registered to the train — that locks track
		// the train never reserved, blocking other trains from routing through it.
		assertThat(registry.getSwitches("train1"))
			.withMessage("a rejected candidate must not leave switches registered to the train")
			.isEmpty()
		assertThat(switches().filter { it.locked }.map { it.name })
			.withMessage("the candidate's switch locks must be undone when its reservation is rolled back")
			.isEmpty()
	}

	@Test
	@DisplayName("the rollback restores the pre-attempt PathInfo and switch registrations exactly")
	fun bypassRollbackRestoresPathInfoAndSwitchesToTheirPreAttemptSnapshots() {
		val registry: PathReservationRegistry = context.scope.get()
		val entry = inOutNamed("A")
		val firstHop = service.reservePath("train1", entry, semaphoreNamed("sem1"))
		assertThat(firstHop).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val pathInfoBefore = requireNotNull(registry.getPathInfo("train1"))
		val switchesBefore = registry.getSwitches("train1").toSet()
		val lockedBefore = switches().associate { it.name to it.locked }

		val sem1 = semaphoreNamed("sem1")
		val requiredNext = blockBetween("RailSemaphore:sem1", "RailSwitch:swB")
		requiredNext.setUpPath(sem1, "other-train")
		service.reservePathToAnyNextSemaphore("train1", sem1, requiredNext)

		// A rejected candidate's PathInfo must not stay merged into the train's navigation metadata:
		// it points through a route that was just released, steering the train onto the rejected path.
		assertThat(registry.getPathInfo("train1"))
			.withMessage("the rolled-back candidate must not leave a PathInfo merged through the rejected route")
			.isSameInstanceAs(pathInfoBefore)
		assertThat(registry.getSwitches("train1").toSet())
			.withMessage("the rolled-back candidate must not leave switches registered beyond the live reservation's")
			.isEqualTo(switchesBefore)
		assertThat(switches().associate { it.name to it.locked })
			.withMessage("candidate switch locks must be undone; the live reservation's locks must survive")
			.isEqualTo(lockedBefore)
	}

	private fun semaphoreNamed(name: String): DynamicRailSemaphore =
		separators().filterIsInstance<DynamicRailSemaphore>().single { it.name == name }

	private fun inOutNamed(name: String): DynamicInOut =
		separators().filterIsInstance<DynamicInOut>().single { it.name == name }

	private fun litSemaphoreNames(): List<String> =
		separators()
			.filterIsInstance<DynamicRailSemaphore>()
			.filter { it.signal.isAllowing() }
			.mapNotNull { it.name }

	private fun separators(): List<DynamicPathSeparator> {
		val grid = context.getRailWayNetGrid()
		val found = mutableListOf<DynamicPathSeparator>()
		for (x in 0 until grid.cols) {
			for (y in 0 until grid.rows) {
				val cell = grid[Point(x, y)]
				if (cell is DynamicRailSemaphore || cell is DynamicInOut) {
					found.add(cell as DynamicPathSeparator)
				}
			}
		}
		return found
	}

	private fun switches(): List<DynamicRailSwitch> {
		val grid = context.getRailWayNetGrid()
		val found = mutableListOf<DynamicRailSwitch>()
		for (x in 0 until grid.cols) {
			for (y in 0 until grid.rows) {
				val cell = grid[Point(x, y)]
				if (cell is DynamicRailSwitch) {
					found.add(cell)
				}
			}
		}
		return found
	}

	/** The block whose `staticRef` names both [a] and [b] as its ends. */
	private fun blockBetween(
		a: String,
		b: String
	): DynamicTrackBlock {
		val grid = context.getRailWayNetGrid()
		val graph = context.getGraph()
		for (x in 0 until grid.cols) {
			for (y in 0 until grid.rows) {
				val cell = grid[Point(x, y)] ?: continue
				val location = grid.getLocation(cell) ?: continue

				@Suppress("UNCHECKED_CAST")
				val edges = runCatching { graph.assignedEdges(location) as Map<*, *> }.getOrNull() ?: continue
				edges.values.forEach { edge ->
					val block = (edge as? TrackSection)?.getTrackBlock() as? DynamicTrackBlock
					val ends = block?.staticRef?.toString()
					if (ends != null && ends.contains(a) && ends.contains(b)) return block
				}
			}
		}
		error("No block between $a and $b in parallel-routes.xml")
	}
}
