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
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.RecordingBlockOccupancyListener
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Pins [CandidateCommitter] (Issue #1211, SP12.10): the candidate commit, finalize and rollback
 * steps extracted from [DefaultPathReservationService]. The service-level suites
 * (`ReservePathConflictRollbackScopeTest`, `PathReservationMergeAbortRollbackTest`,
 * `StartSignalRollbackResetTest`, `PathReservationReleaseAndRollbackBlockTest`,
 * `Issue742RegressionTest`) pin the same rollbacks through `reservePath`; this class pins the
 * moved functions on their own and drives each rollback path on the real registry, so a
 * regression is located without going through the whole reservation.
 *
 * The committer under test is always the one the service builds for itself
 * (`DefaultPathReservationService.candidateCommitter`), or the one of a service rebuilt over the
 * same real collaborators with a single injected fault, so a mis-wired service constructor shows
 * up as a failed assertion on the shared registry instead of passing against a test-built twin.
 *
 * Topology (`vyhybna.xml`): InOuts `A` and `B`; semaphores `zA`, `doA1`, `doA2`, `doB2`, `doB1`,
 * `zB`; switches `vA` and `vB`. `zA`, `doB1` and `doB2` face A to B; `doA1`, `doA2` and `zB` face
 * B to A. Route `zA` to `doB1` crosses `vA`; route `zA` to `B` extends it across `vB`.
 *
 * @since Issue #1211
 */
@DisplayName("Candidate committer (Issue #1211)")
@Timeout(30, unit = TimeUnit.SECONDS)
class CandidateCommitterTest : PathReservationServiceTestBase() {
	private lateinit var committer: CandidateCommitter
	private lateinit var zA: DynamicRailSemaphore
	private lateinit var doA1: DynamicRailSemaphore
	private lateinit var doB1: DynamicRailSemaphore
	private lateinit var vA: DynamicRailSwitch
	private lateinit var vB: DynamicRailSwitch
	private lateinit var inOutB: DynamicPathSeparator

	@BeforeEach
	fun resolveCommitter() {
		committer = (service as DefaultPathReservationService).candidateCommitter
		zA = findSemaphoreByName("zA")
		doA1 = findSemaphoreByName("doA1")
		doB1 = findSemaphoreByName("doB1")
		vA = findSwitchByName("vA")
		vB = findSwitchByName("vB")
		inOutB = inOutNamed("B")
		if (vA.conf != Conf.MAIN) {
			vA.changeConf()
		}
	}

	/** The first topological candidate from [start] to [target], as the committer receives it. */
	private fun candidate(
		trainId: String,
		start: DynamicPathSeparator,
		target: DynamicPathSeparator
	): CandidateRequest {
		val path = navigator.findAllTopologicalPaths(start, target, DEFAULT_MAX_PATH_DEPTH).first()
		val blocks = extractUniqueBlocks(path)
		val forwardBlocks = blocks.filterNot { it.trainName == trainId }
		return CandidateRequest(trainId, start, target, path, blocks, forwardBlocks, 0)
	}

	private fun assertNothingLeftBehind(
		trainId: String,
		blocks: List<DynamicTrackBlock>
	) {
		blocks.forEach { block ->
			assertThat(block.getState(), "state of $block").isEqualTo(TrackFacility.State.FREE)
			assertThat(block.trainName, "trainName of $block").isNull()
			assertThat(registry.getOwner(block), "registry owner of $block").isNull()
		}
		assertThat(registry.getBlocks(trainId), "registered blocks of $trainId").isEmpty()
		assertThat(registry.getSwitches(trainId), "registered switches of $trainId").isEmpty()
		assertThat(registry.getPathInfo(trainId), "registered PathInfo of $trainId").isNull()
	}

	// ── Step 2c: tryAtomicReservation ──

	@Test
	fun `tryAtomicReservation reserves every block from the separator and reports success as null`() {
		val request = candidate("t1", zA, doB1)
		assertThat(request.forwardBlocks).isNotEmpty()

		assertThat(committer.tryAtomicReservation("t1", zA, request.forwardBlocks)).isNull()

		request.forwardBlocks.forEach { block ->
			assertThat(block.getState(), "state of $block").isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.reservedFrom, "reservedFrom of $block").isSameInstanceAs(zA)
			assertThat(block.trainName, "trainName of $block").isEqualTo("t1")
		}
		assertThat(registry.getBlocks("t1"), "the physical reservation registers nothing").isEmpty()
	}

	@Test
	fun `tryAtomicReservation rolls back the blocks reserved so far when a later block is taken`() {
		val request = candidate("t1", zA, inOutB)
		assertThat(request.forwardBlocks.size, "the route needs two blocks to fail on the second").isGreaterThanOrEqualTo(2)
		val (first, second) = request.forwardBlocks
		val foreignSeparator = second.ends().first { it !== zA } as DynamicPathSeparator
		second.setUpPath(foreignSeparator, "other")

		val result = committer.tryAtomicReservation("t1", zA, request.forwardBlocks)

		assertThat(result).isEqualTo(PathReservationService.ReservationResult.AllPathsBlocked(1))
		assertThat(first.getState(), "block reserved before the failure").isEqualTo(TrackFacility.State.FREE)
		assertThat(first.trainName).isNull()
		assertThat(second.getState(), "the foreign reservation survives").isEqualTo(TrackFacility.State.RESERVED)
		assertThat(second.trainName).isEqualTo("other")
	}

	@Test
	fun `tryAtomicReservation maps an unexpected exception to AllPathsBlocked and rolls back`() {
		val request = candidate("t1", zA, doB1)
		val first = request.forwardBlocks.first()
		val failing = mockk<DynamicTrackBlock>(relaxed = true)
		every { failing.setUpPath(any(), any()) } throws IllegalStateException("injected failure")

		val result = committer.tryAtomicReservation("t1", zA, listOf(first, failing))

		assertThat(result).isEqualTo(PathReservationService.ReservationResult.AllPathsBlocked(1))
		assertThat(first.getState(), "the block reserved before the failure is rolled back")
			.isEqualTo(TrackFacility.State.FREE)
	}

	// ── Steps 2d-2i: registerAndFinalizeCandidate ──

	@Test
	fun `registerAndFinalizeCandidate commits a reserved candidate and reports its blocks`() {
		val request = candidate("t1", zA, doB1)
		assertThat(committer.tryAtomicReservation("t1", zA, request.forwardBlocks)).isNull()

		val (failures, outcome) = committer.registerAndFinalizeCandidate(request, GeometricFailures())

		assertThat(failures).isEqualTo(GeometricFailures())
		assertThat(outcome).isEqualTo(PathReservationService.ReservationResult.Success(request.blocks))
		assertThat(registry.getBlocks("t1")).containsExactlyInAnyOrder(*request.blocks.toTypedArray())
		assertThat(registry.getSwitches("t1"), "the route's switch is registered").contains(vA)
		assertThat(vA.locked, "the route's switch is locked").isTrue()
		assertThat(registry.getPathInfo("t1"), "the forward-only PathInfo is registered").isNotNull()
		assertThat(zA.signal.isAllowing(), "the START signal is lit").isTrue()
		assertThat(service.hasClearedSignals("t1"), "the lit START is recorded in the ledger").isTrue()
	}

	@Test
	fun `a route extension announces BlockReserved only for the blocks it newly reserved`() {
		val held = assertReservationSuccess(service.reservePath("t1", zA, doB1)).reservedBlocks
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		val extended = assertReservationSuccess(service.reservePath("t1", zA, inOutB)).reservedBlocks

		val newBlocks = extended.filterNot { it in held }
		assertThat(newBlocks, "the extension reserves at least one new block").isNotEmpty()
		val announced = listener.events.filter { it.type == BlockOccupancyEventType.BLOCK_RESERVED }.map { it.block }
		assertThat(announced).containsExactlyInAnyOrder(*newBlocks.toTypedArray())
	}

	// ── Step 2d conflict arm: rollbackRegistryConflict ──

	@Test
	fun `a registry conflict rolls back only the forward blocks and leaves the held route intact`() {
		val held = assertReservationSuccess(service.reservePath("t1", zA, doB1)).reservedBlocks
		val request = candidate("t1", zA, inOutB)
		assertThat(request.forwardBlocks, "the extension has forward blocks").isNotEmpty()
		val contested = request.forwardBlocks.last()
		assertThat(registry.registerAtomic("other", listOf(contested)))
			.isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()
		assertThat(committer.tryAtomicReservation("t1", zA, request.forwardBlocks)).isNull()

		val (failures, outcome) = committer.registerAndFinalizeCandidate(request, GeometricFailures())

		assertThat(failures, "a conflict is not a geometric failure").isEqualTo(GeometricFailures())
		assertThat(outcome).isEqualTo(PathReservationService.ReservationResult.Conflict(contested, "other"))
		request.forwardBlocks.forEach { block ->
			assertThat(block.getState(), "forward block $block rolled back").isEqualTo(TrackFacility.State.FREE)
		}
		held.forEach { block ->
			assertThat(block.getState(), "held block $block untouched").isEqualTo(TrackFacility.State.RESERVED)
			assertThat(registry.getOwner(block), "owner of held block $block").isEqualTo("t1")
		}
		assertThat(registry.getOwner(contested), "the other train's registration is untouched").isEqualTo("other")
		assertThat(registry.getPathInfo("t1")).isNotNull()
	}

	// ── Step 2g: failed START signal ──

	/**
	 * Forwards every call to [real], except that [configureSemaphoreSignal] for [faultOn] writes the
	 * aspect first and THEN throws -- the partial-write shape `StartSignalRollbackResetTest`
	 * injects, repeated here so the rollback is pinned beside the committer that performs it.
	 */
	private class PartialWriteFaultEnvironment(
		private val real: SimulationContext,
		private val faultOn: DynamicRailSemaphore
	) : SimulationContext by real {
		override fun configureSemaphoreSignal(
			semaphore: DynamicRailSemaphore,
			firstBlock: DynamicTrackBlock,
			allowedSpeed: Double?
		) {
			real.configureSemaphoreSignal(semaphore, firstBlock, allowedSpeed)
			if (semaphore === faultOn) {
				throw SimulationException("injected signal-config failure for ${semaphore.name}")
			}
		}
	}

	@Test
	fun `a failed START signal rolls the candidate back and leaves nothing registered`() {
		val faulty =
			DefaultPathReservationService(
				navigator,
				PartialWriteFaultEnvironment(simulationContext, doA1),
				registry,
				simulationContext.scope.get<PathInfoBuilder>(),
				simulationContext.scope.get<RouteFinder>()
			)
		val candidateBlocks = candidate("faultTrain", doA1, inOutNamed("A")).blocks

		val result = faulty.reservePath("faultTrain", doA1, inOutNamed("A"), maxDepth = 4)

		assertThat(result).isEqualTo(PathReservationService.ReservationResult.AllPathsBlocked(1))
		assertNothingLeftBehind("faultTrain", candidateBlocks)
		assertThat(vA.locked, "the candidate's switch is released").isFalse()
		assertThat(doA1.signal, "the partially written START aspect is reset").isEqualTo(Signal.STOP)
	}

	@Test
	fun `a START facing away from the direction of travel is recorded as a geometric impossibility`() {
		// doA1 faces B to A, so a route from it towards B has a rear-facing START (G4).
		val result = service.reservePath("t1", doA1, inOutB)

		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()
		assertThat((result as PathReservationService.ReservationResult.GeometricallyImpossible).reason)
			.contains("faces away from the requested direction of travel")
		assertNothingLeftBehind("t1", candidate("t1", doA1, inOutB).blocks)
		assertThat(doA1.signal).isEqualTo(Signal.STOP)
		assertThat(vA.locked).isFalse()
	}

	// ── Step 2i: rollbackIfMergeAborted ──

	/**
	 * A [DefaultPathReservationService] over a spy of the real registry whose
	 * `registerPathInfo` reports `Aborted` from the [abortFromCall]-th call on (1-based); earlier
	 * calls pass through, so the rollback runs against real registry state.
	 */
	private fun abortingService(abortFromCall: Int): DefaultPathReservationService {
		val spyRegistry = spyk(registry)
		var calls = 0
		every { spyRegistry.registerPathInfo(any(), any()) } answers {
			calls++
			if (calls < abortFromCall) {
				callOriginal()
			} else {
				PathReservationRegistry.MergeOutcome.Aborted(
					secondArg<PathInfo>(),
					"unit-test forced abort (not a real merger cause)"
				)
			}
		}
		return DefaultPathReservationService(
			navigator = navigator,
			environment = environment,
			registry = spyRegistry,
			pathInfoBuilder = simulationContext.scope.get(),
			routeFinder = simulationContext.scope.get<RouteFinder>()
		)
	}

	@Test
	fun `a merge abort releases the candidate and registerAndFinalizeCandidate rejects it`() {
		val aborting = abortingService(abortFromCall = 1).candidateCommitter
		val request = candidate("t1", zA, doB1)
		assertThat(aborting.tryAtomicReservation("t1", zA, request.forwardBlocks)).isNull()

		val (failures, outcome) = aborting.registerAndFinalizeCandidate(request, GeometricFailures())

		assertThat(outcome, "a rejected candidate has no terminal result").isNull()
		assertThat(failures, "a merge abort is not a geometric failure").isEqualTo(GeometricFailures())
		assertNothingLeftBehind("t1", request.blocks)
		assertThat(vA.locked, "the candidate's switch is released").isFalse()
		assertThat(zA.signal, "the candidate's START is reset").isEqualTo(Signal.STOP)
	}

	@Test
	fun `a merge abort on a route extension rolls back only what the extension acquired`() {
		val aborting = abortingService(abortFromCall = 2)
		val held = assertReservationSuccess(aborting.reservePath("t1", zA, doB1)).reservedBlocks
		val heldSwitches = registry.getSwitches("t1").toSet()
		val heldPathInfo = registry.getPathInfo("t1")
		assertThat(heldSwitches, "the held route crosses vA").contains(vA)
		assertThat(zA.signal.isAllowing(), "the held route's START is lit").isTrue()

		val extension = aborting.reservePath("t1", zA, inOutB)

		assertThat(extension, "every candidate's merge aborts")
			.isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		assertThat(registry.getBlocks("t1")).containsExactlyInAnyOrder(*held.toTypedArray())
		held.forEach { block ->
			assertThat(block.getState(), "held block $block").isEqualTo(TrackFacility.State.RESERVED)
		}
		assertThat(registry.getSwitches("t1").toSet(), "the earlier hop's switches survive").isEqualTo(heldSwitches)
		assertThat(vB.locked, "the extension's own switch is released").isFalse()
		assertThat(registry.getPathInfo("t1"), "the held PathInfo is untouched").isSameInstanceAs(heldPathInfo)
		assertThat(zA.signal.isAllowing(), "the held route's START survives the rollback").isTrue()
		assertThat(registry.getBlocks("t1"), "registered blocks").hasSize(held.size)
	}

	// ── rollbackUnconfigurableCandidate ──

	/** The train holds `zA` to `doB1` (switch `vA`) and has just extended it to `B` (switch `vB`). */
	private class HeldAndExtended(
		val held: List<DynamicTrackBlock>,
		val forward: List<DynamicTrackBlock>,
		val switches: List<DynamicRailSwitch>,
		val priorSwitches: Set<DynamicRailSwitch>
	)

	private fun holdAndExtend(trainId: String): HeldAndExtended {
		val held = assertReservationSuccess(service.reservePath(trainId, zA, doB1)).reservedBlocks
		val priorSwitches = registry.getSwitches(trainId).toSet()
		val extended = assertReservationSuccess(service.reservePath(trainId, zA, inOutB)).reservedBlocks
		val forward = extended.filterNot { it in held }
		assertThat(forward, "the extension reserved new blocks").isNotEmpty()
		val switches = registry.getSwitches(trainId).toList()
		assertThat(switches.toSet(), "the extension crosses a second switch").isEqualTo(setOf(vA, vB))
		assertThat(priorSwitches).isEqualTo(setOf(vA))
		return HeldAndExtended(held, forward, switches, priorSwitches)
	}

	@Test
	fun `rollbackUnconfigurableCandidate releases only the switches the train did not already own`() {
		val route = holdAndExtend("t1")

		committer.rollbackUnconfigurableCandidate("t1", route.forward, route.switches, route.priorSwitches)

		assertThat(registry.getSwitches("t1").toSet(), "the prior switch stays registered").isEqualTo(setOf(vA))
		assertThat(vA.locked, "the prior switch stays locked").isTrue()
		assertThat(registry.getSwitchOwner(vB), "the candidate's own switch is unregistered").isNull()
		assertThat(vB.locked, "the candidate's own switch is unlocked").isFalse()
	}

	@Test
	fun `rollbackUnconfigurableCandidate touches only the given forward blocks`() {
		val route = holdAndExtend("t1")

		committer.rollbackUnconfigurableCandidate("t1", route.forward, route.switches, route.priorSwitches)

		route.forward.forEach { block ->
			assertThat(block.getState(), "forward block $block").isEqualTo(TrackFacility.State.FREE)
			assertThat(registry.getOwner(block), "owner of forward block $block").isNull()
		}
		route.held.forEach { block ->
			assertThat(block.getState(), "held block $block").isEqualTo(TrackFacility.State.RESERVED)
			assertThat(registry.getOwner(block), "owner of held block $block").isEqualTo("t1")
		}
	}

	@Test
	fun `rollbackUnconfigurableCandidate publishes no release event`() {
		val request = candidate("t1", zA, doB1)
		assertThat(committer.tryAtomicReservation("t1", zA, request.forwardBlocks)).isNull()
		assertThat(registry.registerAtomic("t1", request.forwardBlocks))
			.isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		committer.rollbackUnconfigurableCandidate("t1", request.forwardBlocks, emptyList(), emptySet())

		assertNothingLeftBehind("t1", request.forwardBlocks)
		assertThat(listener.events.filter { it.type == BlockOccupancyEventType.BLOCK_RELEASED }).isEmpty()
	}
}
