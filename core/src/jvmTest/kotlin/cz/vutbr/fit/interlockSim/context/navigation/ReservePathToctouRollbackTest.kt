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
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Time-of-check/time-of-use rollbacks of [PathReservationService.reservePath] (Issue #1275, SP6.2
 * follow-up of PR #1265).
 *
 * A normal denial never reaches [CandidateCommitter]'s two block rollbacks: the Step 2b screen
 * (`areAllFreeOrOwnedBy`) refuses a candidate before any block is taken. These tests open the
 * window between that check and the use: the candidate's FIRST forward block is turned into an
 * in-place MockK spy whose `setUpPath` for the requesting train runs the real reservation and then
 * lets another train take the candidate's LAST forward block -- inside Step 2c, after Step 2b passed
 * the whole candidate. No production seam exists between the two steps (the block emits no event of
 * its own; `BLOCK_RESERVED` goes out only after Step 2d), so the spy is the fixture. Two shapes:
 *
 * - the block is taken physically (`setUpPath` from a foreign separator), so Step 2c's next
 *   `setUpPath` throws and `tryAtomicReservation`'s catch path rolls the partial reservation back;
 * - the block is taken in the registry only (`registerAtomic`), so Step 2c succeeds and Step 2d's
 *   `registerAtomic` conflicts, and `rollbackRegistryConflict` rolls the forward blocks back.
 *
 * Either way the request must leave no trace: every block's state, holder, separator reservation
 * and registry owner, every switch's position, lock and claim, and every signal aspect are as they
 * were before the call, except for the block the other train took, which stays the other train's.
 * Dropping either `rollbackReservation` call leaves the blocks reserved before the failure
 * RESERVED from `zA` for the requesting train, which these snapshots catch.
 *
 * Topology (`vyhybna.xml`): route `zA` to InOut `B` crosses `vA` and `vB`; its last block is common
 * to every candidate, so after the rollback no other candidate can succeed.
 *
 * @since Issue #1275
 */
@DisplayName("reservePath time-of-check/time-of-use rollback (Issue #1275)")
@Timeout(30, unit = TimeUnit.SECONDS)
class ReservePathToctouRollbackTest : PathReservationServiceTestBase() {
	private lateinit var zA: DynamicPathSeparator
	private lateinit var inOutB: DynamicPathSeparator
	private lateinit var forwardBlocks: List<DynamicTrackBlock>
	private lateinit var trigger: DynamicTrackBlock
	private lateinit var contested: DynamicTrackBlock
	private var triggerFired = false

	/** Everything about one block a reservation or its rollback can change. */
	private data class BlockSnapshot(
		val state: TrackFacility.State,
		val trainName: String?,
		val reservedFrom: PathSeparator?,
		val registryOwner: String?
	)

	/** Everything about one switch a reservation or its rollback can change. */
	private data class SwitchSnapshot(
		val conf: Conf,
		val locked: Boolean,
		val owner: String?
	)

	private fun allBlocks(): List<DynamicTrackBlock> =
		simulationContext
			.getGraph()
			.values()
			.filterIsInstance<DynamicTrackBlock>()
			.distinct()

	private fun blockSnapshots(): Map<DynamicTrackBlock, BlockSnapshot> =
		allBlocks().associateWith {
			BlockSnapshot(it.getState(), it.trainName, it.reservedFrom, registry.getOwner(it))
		}

	private fun switchSnapshots(): Map<DynamicRailSwitch, SwitchSnapshot> =
		simulationContext.cellsOfType<DynamicRailSwitch>().associateWith {
			SwitchSnapshot(it.conf, it.locked, registry.getSwitchOwner(it))
		}

	@BeforeEach
	fun resolveRoute() {
		zA = findSemaphoreByName("zA")
		inOutB = inOutNamed("B")
		val candidates = navigator.findAllTopologicalPaths(zA, inOutB, DEFAULT_MAX_PATH_DEPTH)
		assertThat(candidates, "candidate paths from zA to B").isNotEmpty()
		forwardBlocks = extractUniqueBlocks(candidates.first()).excludingOwnedBy(TRAIN)
		assertThat(forwardBlocks.size, "the route needs a block before the contested one")
			.isGreaterThanOrEqualTo(2)
		trigger = forwardBlocks.first()
		contested = forwardBlocks.last()
		candidates.forEach { path ->
			assertThat(contested in extractUniqueBlocks(path), "every candidate crosses the contested block")
				.isTrue()
		}
		assertThat(allBlocks().all { it.getState() == TrackFacility.State.FREE }, "the network starts FREE")
			.isTrue()
	}

	@AfterEach
	fun unmockTrigger() {
		unmockkObject(trigger)
	}

	/**
	 * Runs [act] once, right after [TRAIN]'s real `setUpPath` of [trigger] -- inside Step 2c, after
	 * the Step 2b screen passed the whole candidate.
	 */
	private fun onTriggerReserved(act: () -> Unit) {
		mockkObject(trigger)
		every { trigger.setUpPath(any(), TRAIN) } answers {
			callOriginal()
			if (!triggerFired) {
				triggerFired = true
				act()
			}
		}
	}

	private fun assertOnlyTheContestedBlockChanged(
		before: Map<DynamicTrackBlock, BlockSnapshot>,
		contestedAfter: BlockSnapshot
	) {
		val after = blockSnapshots()
		before.forEach { (block, snapshot) ->
			val expected = if (block === contested) contestedAfter else snapshot
			assertThat(after.getValue(block), "block $block after the rolled-back request").isEqualTo(expected)
		}
	}

	private fun assertNoTraceOfTheRequest(
		switchesBefore: Map<DynamicRailSwitch, SwitchSnapshot>,
		aspectsBefore: Map<String, Signal>
	) {
		assertThat(registry.getBlocks(TRAIN), "registered blocks of $TRAIN").isEmpty()
		assertThat(registry.getSwitches(TRAIN), "registered switches of $TRAIN").isEmpty()
		assertThat(registry.getPathInfo(TRAIN), "registered PathInfo of $TRAIN").isNull()
		assertThat(service.hasClearedSignals(TRAIN), "cleared signals of $TRAIN").isFalse()
		assertThat(switchSnapshots(), "every switch's position, lock and claim").isEqualTo(switchesBefore)
		assertThat(semaphoreAspects(), "every signal aspect").isEqualTo(aspectsBefore)
	}

	@Test
	fun `a block taken physically between the Step 2b check and Step 2c leaves no trace of the request`() {
		val foreign = contested.ends().first { it !== zA } as DynamicPathSeparator
		onTriggerReserved { contested.setUpPath(foreign, OTHER) }
		val blocksBefore = blockSnapshots()
		val switchesBefore = switchSnapshots()
		val aspectsBefore = semaphoreAspects()

		val result = service.reservePath(TRAIN, zA, inOutB)

		assertThat(result, "the request fails as contention")
			.isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		assertThat(triggerFired, "the other train acted inside Step 2c").isTrue()
		assertOnlyTheContestedBlockChanged(
			blocksBefore,
			BlockSnapshot(TrackFacility.State.RESERVED, OTHER, foreign, null)
		)
		assertThat(contested.reservedFrom, "the other train's reservation survives").isSameInstanceAs(foreign)
		assertNoTraceOfTheRequest(switchesBefore, aspectsBefore)
	}

	@Test
	fun `a block registered by another train between the Step 2b check and Step 2d leaves no trace of the request`() {
		onTriggerReserved {
			assertThat(registry.registerAtomic(OTHER, listOf(contested)), "the other train's registration")
				.isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()
		}
		val blocksBefore = blockSnapshots()
		val switchesBefore = switchSnapshots()
		val aspectsBefore = semaphoreAspects()

		val result = service.reservePath(TRAIN, zA, inOutB)

		assertThat(triggerFired, "the other train acted inside Step 2c").isTrue()
		assertThat(result).isEqualTo(PathReservationService.ReservationResult.Conflict(contested, OTHER))
		assertOnlyTheContestedBlockChanged(
			blocksBefore,
			BlockSnapshot(TrackFacility.State.FREE, null, null, OTHER)
		)
		assertNoTraceOfTheRequest(switchesBefore, aspectsBefore)
	}

	private companion object {
		const val TRAIN = "t1"
		const val OTHER = "other"
	}
}
