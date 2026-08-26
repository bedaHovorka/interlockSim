/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * SP2b.3: PathCommandTranslator — switch + signal command generation (Issue #558).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathCandidate
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.Util
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * Integration tests for [PathCommandTranslator] — the switch-and-signal command generator
 * introduced by SP2b.3 (Issue #558).
 *
 * ## Test network
 *
 * All tests use the `vyhybna.xml` shunting loop network:
 *
 * ```
 * InOutA(11,8) ─ kA ─ zA(14,8) ─┬─ k0 ─ vA(15,8) ─┬─ k1 ─ doA1(16,8) ─ k2 ─...─ doB1(25,8) ─ vB(26,8) ─┬─ kB ─ zB(27,8) ─ InOutB(30,8)
 *                                 └──────────────────┘           └─ k3 ─ doA2(17,9) ─ k4 ─...─ doB2(24,9) ─┘
 * ```
 *
 * ## What is verified
 *
 * - `zA → doA1` path: vA must be set to MAIN; zA must emit `SetSignalAspect(FREE)`
 * - `zA → doA2` path: vA must be set to BRANCH; zA must emit `SetSignalAspect(FREE)`
 * - Start separator that is a [DynamicInOut] (not a semaphore): no signal command emitted
 * - Path with no switches: only signal command emitted
 * - Empty sections list: only the entry-signal command is emitted (when start is a
 *   semaphore); no switch commands
 *
 * @since Issue #558 (SP2b.3 — Goal 10)
 */
@DisplayName("PathCommandTranslator — SP2b.3 switch + signal command generation")
@Tag("integration-test")
@Timeout(60, unit = TimeUnit.SECONDS)
class PathCommandTranslatorTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val processFactory: SimulationProcessFactory by inject()

	private lateinit var context: SimulationContext
	private lateinit var navigator: TopologyNavigator

	// vyhybna.xml element coords (same as ShuntingLoop companion constants)
	private lateinit var zA: DynamicRailSemaphore // (14,8) entry semaphore
	private lateinit var doA1: DynamicRailSemaphore // (16,8) upper-route exit semaphore
	private lateinit var doA2: DynamicRailSemaphore // (17,9) lower-route exit semaphore
	private lateinit var vA: DynamicRailSwitch // (15,8) entry switch
	private lateinit var inOutA: DynamicInOut // (11,8) InOut A
	private lateinit var vB: DynamicRailSwitch // (26,8) exit switch
	private lateinit var zB: DynamicRailSemaphore // (27,8) exit semaphore toward InOut B

	@BeforeEach
	fun setUp() {
		val editingCtx = TestFixtures.loadShuntingEditingContext(editingContextFactory)
		context = ContextTransformer.createSimulationContext(editingCtx, processFactory)

		navigator = context.getRoutingServices().getTopologyNavigator()

		zA = elementAt(14, 8)
		doA1 = elementAt(16, 8)
		doA2 = elementAt(17, 9)
		vA = elementAt(15, 8)
		inOutA = elementAt(11, 8)
		vB = elementAt(26, 8)
		zB = elementAt(27, 8)
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private inline fun <reified T : Cell> elementAt(
		x: Int,
		y: Int
	): T {
		val cell =
			context.getRailWayNetGrid()[Point(x, y)]
				?: throw AssertionError("No cell at ($x,$y) in vyhybna.xml")
		return Util.assertInstanceOf(cell)
	}

	/**
	 * Find the single [PathCandidate] from [start] to [target] that passes through switch
	 * [vA] in configuration [wantConf].
	 *
	 * vyhybna.xml has two routes between zA and doA1/doA2; select the one with the correct
	 * switch configuration by inspecting the actual switch-command output of
	 * [PathCommandTranslator.translate]. This disambiguates **by translator output property**
	 * (the emitted `SetSwitchPosition` for vA), not by candidate topology — but it is not
	 * tautological against the assertions, because the tests cross-check `position` against
	 * the `RailSwitch.Conf.MAIN`/`BRANCH` constants rather than against values derived from
	 * the translator.
	 */
	private fun findCandidateWithConf(
		start: DynamicRailSemaphore,
		target: DynamicRailSemaphore,
		wantConf: RailSwitch.Conf
	): PathCandidate {
		val candidates = navigator.findCandidatePaths(start, target)
		check(candidates.isNotEmpty()) { "No candidate path from ${start.name} to ${target.name}" }
		return candidates.first { candidate ->
			val commands = PathCommandTranslator.translate("train1", start, candidate, context)
			commands
				.filterIsInstance<DispatchDecision.SetSwitchPosition>()
				.any { it.switchName == vA.staticRef.getName() && it.position == wantConf }
		}
	}

	// ── Happy path: upper route (MAIN) ───────────────────────────────────────

	@Nested
	@DisplayName("zA → doA1 path (switch vA MAIN)")
	inner class MainRoutePath {
		@Test
		@DisplayName("emits SetSwitchPosition(vA, MAIN) for the upper route")
		fun switchCommandMain() {
			val candidate = findCandidateWithConf(zA, doA1, RailSwitch.Conf.MAIN)

			val commands = PathCommandTranslator.translate("train1", zA, candidate, context)

			val switchCmds = commands.filterIsInstance<DispatchDecision.SetSwitchPosition>()
			assertThat(switchCmds.size).isEqualTo(1)
			assertThat(switchCmds[0].switchName).isEqualTo(vA.staticRef.getName())
			assertThat(switchCmds[0].position).isEqualTo(RailSwitch.Conf.MAIN)
		}

		@Test
		@DisplayName("emits SetSignalAspect(zA, FREE) for the entry semaphore")
		fun signalCommandFree() {
			val candidate = findCandidateWithConf(zA, doA1, RailSwitch.Conf.MAIN)

			val commands = PathCommandTranslator.translate("train1", zA, candidate, context)

			val signalCmds = commands.filterIsInstance<DispatchDecision.SetSignalAspect>()
			assertThat(signalCmds.size).isEqualTo(1)
			assertThat(signalCmds[0].semaphoreName).isEqualTo(zA.name)
			assertThat(signalCmds[0].signal).isEqualTo(Signal.FREE)
		}

		@Test
		@DisplayName("switch commands precede signal command in the returned list")
		fun switchBeforeSignalOrdering() {
			val candidate = findCandidateWithConf(zA, doA1, RailSwitch.Conf.MAIN)

			val commands = PathCommandTranslator.translate("train1", zA, candidate, context)

			// Both command types must be present
			val lastSwitchIndex = commands.indexOfLast { it is DispatchDecision.SetSwitchPosition }
			val firstSignalIndex = commands.indexOfFirst { it is DispatchDecision.SetSignalAspect }
			assertThat(lastSwitchIndex).isGreaterThanOrEqualTo(0)
			assertThat(firstSignalIndex).isGreaterThanOrEqualTo(0)
			// All SetSwitchPosition decisions appear before the first SetSignalAspect
			assertThat(lastSwitchIndex).isLessThan(firstSignalIndex)
		}
	}

	// ── Happy path: lower route (BRANCH) ─────────────────────────────────────

	@Nested
	@DisplayName("zA → doA2 path (switch vA BRANCH)")
	inner class BranchRoutePath {
		@Test
		@DisplayName("emits SetSwitchPosition(vA, BRANCH) for the lower route")
		fun switchCommandBranch() {
			val candidate = findCandidateWithConf(zA, doA2, RailSwitch.Conf.BRANCH)

			val commands = PathCommandTranslator.translate("train1", zA, candidate, context)

			val switchCmds = commands.filterIsInstance<DispatchDecision.SetSwitchPosition>()
			assertThat(switchCmds.size).isEqualTo(1)
			assertThat(switchCmds[0].switchName).isEqualTo(vA.staticRef.getName())
			assertThat(switchCmds[0].position).isEqualTo(RailSwitch.Conf.BRANCH)
		}

		@Test
		@DisplayName("emits SetSignalAspect(zA, FREE) for the entry semaphore")
		fun signalCommandFree() {
			val candidate = findCandidateWithConf(zA, doA2, RailSwitch.Conf.BRANCH)

			val commands = PathCommandTranslator.translate("train1", zA, candidate, context)

			val signalCmds = commands.filterIsInstance<DispatchDecision.SetSignalAspect>()
			assertThat(signalCmds.size).isEqualTo(1)
			assertThat(signalCmds[0].semaphoreName).isEqualTo(zA.name)
			assertThat(signalCmds[0].signal).isEqualTo(Signal.FREE)
		}

		@Test
		@DisplayName("switch commands differ between MAIN and BRANCH routes")
		fun mainAndBranchCommandsDiffer() {
			val mainCandidate = findCandidateWithConf(zA, doA1, RailSwitch.Conf.MAIN)
			val branchCandidate = findCandidateWithConf(zA, doA2, RailSwitch.Conf.BRANCH)

			val mainCmds =
				PathCommandTranslator
					.translate("t", zA, mainCandidate, context)
					.filterIsInstance<DispatchDecision.SetSwitchPosition>()
			val branchCmds =
				PathCommandTranslator
					.translate("t", zA, branchCandidate, context)
					.filterIsInstance<DispatchDecision.SetSwitchPosition>()

			assertThat(mainCmds[0].position).isEqualTo(RailSwitch.Conf.MAIN)
			assertThat(branchCmds[0].position).isEqualTo(RailSwitch.Conf.BRANCH)
		}
	}

	// ── Edge cases ────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Edge cases")
	inner class EdgeCases {
		@Test
		@DisplayName("start is DynamicInOut — no signal command emitted")
		fun noSignalCommandForInOut() {
			// Path from InOut A (not a semaphore) to zA
			val candidates = navigator.findCandidatePaths(inOutA, zA)
			check(candidates.isNotEmpty()) { "No path from InOutA to zA" }
			val candidate = candidates.first()

			val commands = PathCommandTranslator.translate("train1", inOutA, candidate, context)

			val signalCmds = commands.filterIsInstance<DispatchDecision.SetSignalAspect>()
			assertThat(signalCmds).isEmpty()
		}

		@Test
		@DisplayName("empty sections list — only signal command emitted, no switch commands")
		fun emptySectionsEmptyCommands() {
			val emptyCandidate = PathCandidate(sections = emptyList(), switchMovementCount = 0)

			val commands = PathCommandTranslator.translate("train1", zA, emptyCandidate, context)

			// No switch commands for an empty path (no sections to traverse)
			val switchCmds = commands.filterIsInstance<DispatchDecision.SetSwitchPosition>()
			assertThat(switchCmds).isEmpty()

			// But the entry signal (zA is a semaphore) must still be requested
			val signalCmds = commands.filterIsInstance<DispatchDecision.SetSignalAspect>()
			assertThat(signalCmds).hasSize(1)
		}

		@Test
		@DisplayName("rationale includes trainId")
		fun rationaleContainsTrainId() {
			val candidate = findCandidateWithConf(zA, doA1, RailSwitch.Conf.MAIN)

			val commands = PathCommandTranslator.translate("myTrain42", zA, candidate, context)

			commands.forEach { decision ->
				val rationale = decision.rationale ?: return@forEach
				assertThat(rationale).isEqualTo(
					when (decision) {
						is DispatchDecision.SetSwitchPosition ->
							listOf("SP2b.3: switch on selected path for myTrain42")
						is DispatchDecision.SetSignalAspect ->
							listOf("SP2b.3: entry signal on selected path for myTrain42")
						else -> return@forEach
					}
				)
			}
		}

		@Test
		@DisplayName("emitted SetSignalAspect carries the trainId (G5, Issue #893 task A6)")
		fun signalCommandCarriesTrainId() {
			val candidate = findCandidateWithConf(zA, doA1, RailSwitch.Conf.MAIN)

			val commands = PathCommandTranslator.translate("myTrain42", zA, candidate, context)

			val signalCmds = commands.filterIsInstance<DispatchDecision.SetSignalAspect>()
			assertThat(signalCmds.size).isEqualTo(1)
			assertThat(signalCmds[0].trainName).isEqualTo("myTrain42")
		}

		@Test
		@DisplayName("returned decisions are concrete DispatchDecision subtypes")
		fun commandTypesAreDispatchDecisions() {
			val candidate = findCandidateWithConf(zA, doA1, RailSwitch.Conf.MAIN)

			val commands = PathCommandTranslator.translate("train1", zA, candidate, context)

			commands.forEach { decision ->
				assertThat(decision).isInstanceOf<DispatchDecision>()
			}
		}
	}

	// ── Start separator handling ─────────────────────────────────────────────

	@Nested
	@DisplayName("Start separator handling")
	inner class StartSeparatorHandling {
		@Test
		@DisplayName("static RailSemaphore start — signal emitted via static-fallback branch")
		fun staticRailSemaphoreStartEmitsSignal() {
			// Pass the STATIC RailSemaphore (not the Dynamic wrapper) as start — exercises
			// the CellUtilities.assertNodeCell(start) is RailSemaphore fallback branch.
			val staticZA = CellUtilities.assertNodeCell(zA)
			check(staticZA is RailSemaphore) { "zA should resolve to a static RailSemaphore" }

			val candidate = findCandidateWithConf(zA, doA1, RailSwitch.Conf.MAIN)

			val commands = PathCommandTranslator.translate("train1", staticZA, candidate, context)

			val signalCmds = commands.filterIsInstance<DispatchDecision.SetSignalAspect>()
			assertThat(signalCmds.size).isEqualTo(1)
			assertThat(signalCmds[0].semaphoreName).isEqualTo(zA.name)
			assertThat(signalCmds[0].signal).isEqualTo(Signal.FREE)

			// Traversal still works with a static start (getSecondEnd handles static
			// separators; toDynamic re-wraps the static other-end), so the mid-path switch
			// is still commanded.
			val switchCmds = commands.filterIsInstance<DispatchDecision.SetSwitchPosition>()
			assertThat(switchCmds.size).isEqualTo(1)
			assertThat(switchCmds[0].switchName).isEqualTo(vA.staticRef.getName())
			assertThat(switchCmds[0].position).isEqualTo(RailSwitch.Conf.MAIN)
		}

		@Test
		@DisplayName("terminal separator is a switch — no SetSwitchPosition emitted for it")
		fun terminalSwitchIsNotConfigured() {
			// Path zA → vA: vA is the terminal separator (no outgoing section), so the
			// nextSection == null guard skips it even though it is a DynamicRailSwitch.
			val candidates = navigator.findCandidatePaths(zA, vA)
			check(candidates.isNotEmpty()) { "No candidate path from zA to vA" }
			val candidate = candidates.first()

			val commands = PathCommandTranslator.translate("train1", zA, candidate, context)

			val switchCmds = commands.filterIsInstance<DispatchDecision.SetSwitchPosition>()
			assertThat(switchCmds).isEmpty()

			val signalCmds = commands.filterIsInstance<DispatchDecision.SetSignalAspect>()
			assertThat(signalCmds.size).isEqualTo(1)
			assertThat(signalCmds[0].semaphoreName).isEqualTo(zA.name)
		}

		@Test
		@DisplayName("DynamicRailSwitch start — entry switch not reconfigured, no signal emitted")
		fun switchStartIsNotReconfigured() {
			// Start at vA (a switch) and route to zB. The entry switch vA must NOT receive a
			// SetSwitchPosition (the dispatcher's permissive request assumes the train already
			// occupies start); only the mid-path switch vB is commanded. No signal is emitted
			// because the start is not a semaphore.
			val candidates = navigator.findCandidatePaths(vA, zB)
			check(candidates.isNotEmpty()) { "No candidate path from vA to zB" }
			val candidate = candidates.first()

			val commands = PathCommandTranslator.translate("train1", vA, candidate, context)

			val switchCmds = commands.filterIsInstance<DispatchDecision.SetSwitchPosition>()
			// Exactly one switch command — for the mid-path switch vB, never for the start vA.
			assertThat(switchCmds).hasSize(1)
			assertThat(switchCmds[0].switchName).isEqualTo(vB.staticRef.getName())
			assertThat(switchCmds.none { it.switchName == vA.staticRef.getName() }).isTrue()

			val signalCmds = commands.filterIsInstance<DispatchDecision.SetSignalAspect>()
			assertThat(signalCmds).isEmpty()
		}
	}

	// ── Multi-switch path ordering ───────────────────────────────────────────

	@Nested
	@DisplayName("Multi-switch path ordering")
	inner class MultiSwitchPathOrdering {
		@Test
		@DisplayName("zA → zB upper route — two switch commands (vA, vB) before one signal")
		fun twoSwitchesBeforeSignal() {
			val candidate = findCandidateWithConf(zA, zB, RailSwitch.Conf.MAIN)

			val commands = PathCommandTranslator.translate("train1", zA, candidate, context)

			val switchCmds = commands.filterIsInstance<DispatchDecision.SetSwitchPosition>()
			assertThat(switchCmds).hasSize(2)
			val switchNames = switchCmds.map { it.switchName }
			assertThat(switchNames).contains(vA.staticRef.getName())
			assertThat(switchNames).contains(vB.staticRef.getName())
			assertThat(switchCmds.map { it.position }).contains(RailSwitch.Conf.MAIN)

			val signalCmds = commands.filterIsInstance<DispatchDecision.SetSignalAspect>()
			assertThat(signalCmds).hasSize(1)
			assertThat(signalCmds[0].semaphoreName).isEqualTo(zA.name)

			// Both switch commands precede the single signal command.
			val lastSwitchIndex = commands.indexOfLast { it is DispatchDecision.SetSwitchPosition }
			val firstSignalIndex = commands.indexOfFirst { it is DispatchDecision.SetSignalAspect }
			assertThat(lastSwitchIndex).isGreaterThanOrEqualTo(0)
			assertThat(firstSignalIndex).isGreaterThanOrEqualTo(0)
			assertThat(lastSwitchIndex).isLessThan(firstSignalIndex)
		}
	}

	// ── Defensive null-branches (mocked context) ─────────────────────────────

	@Nested
	@DisplayName("Defensive null-branches (mocked context)")
	inner class DefensiveNullBranches {
		// Real vyhybna paths always yield a non-null conf and non-null segments: the real
		// getSegment throws rather than returns null for TrackSection inputs, and every real
		// route through vA joins a stored segment pair. To cover the translator's defensive
		// null/skip branches we mock a SimulationContext that returns controlled segments,
		// while harvesting real Cell.Segment values from the live context so the confs lookup
		// is meaningful.

		/**
		 * Harvest vA's two diverging-arm segments (MAIN arm toward doA1, BRANCH arm toward
		 * doA2). The pair (mainArm, branchArm) is never directly joined in vA.confs (only
		 * merging↔mainDir and merging↔branch are stored), so `confs.get(mainArm, branchArm)`
		 * is null — the premise of the conf==null test below.
		 */
		private fun vAArmSegments(): Pair<Cell.Segment, Cell.Segment> {
			val mainArmSection = findCandidateWithConf(zA, doA1, RailSwitch.Conf.MAIN).sections[1]
			val branchArmSection = findCandidateWithConf(zA, doA2, RailSwitch.Conf.BRANCH).sections[1]
			val mainArm =
				context.getSegment(vA, mainArmSection)
					?: error("mainArm segment should be resolvable for vA")
			val branchArm =
				context.getSegment(vA, branchArmSection)
					?: error("branchArm segment should be resolvable for vA")
			assertThat(vA.staticRef.confs.get(mainArm, branchArm)).isNull()
			return mainArm to branchArm
		}

		private fun mockedContext(
			fromSegment: Cell.Segment?,
			toSegment: Cell.Segment?,
			sec1: TrackSection,
			sec2: TrackSection
		): SimulationContext {
			val mockContext = mockk<SimulationContext>()
			every { mockContext.toDynamic(any<PathSeparator>()) } returns vA
			every { mockContext.getSegment(eq(vA), eq(sec1), eq(sec2)) } returns fromSegment
			every { mockContext.getSegment(eq(vA), eq(sec2), eq(sec1)) } returns toSegment
			return mockContext
		}

		@Test
		@DisplayName("conf == null (impossible switch join) — SetSwitchPosition skipped")
		fun confNullSkipsSwitchCommand() {
			val (mainArm, branchArm) = vAArmSegments()
			val sec1 = mockk<TrackSection>(relaxed = true)
			val sec2 = mockk<TrackSection>(relaxed = true)
			val mockContext = mockedContext(mainArm, branchArm, sec1, sec2)

			val candidate = PathCandidate(sections = listOf(sec1, sec2), switchMovementCount = 1)

			val commands = PathCommandTranslator.translate("train1", zA, candidate, mockContext)

			val switchCmds = commands.filterIsInstance<DispatchDecision.SetSwitchPosition>()
			assertThat(switchCmds).isEmpty()

			val signalCmds = commands.filterIsInstance<DispatchDecision.SetSignalAspect>()
			assertThat(signalCmds).hasSize(1)
			assertThat(signalCmds[0].semaphoreName).isEqualTo(zA.name)
		}

		@Test
		@DisplayName("from == null (undeterminable segments) — SetSwitchPosition skipped")
		fun fromNullSkipsSwitchCommand() {
			val (_, branchArm) = vAArmSegments()
			val sec1 = mockk<TrackSection>(relaxed = true)
			val sec2 = mockk<TrackSection>(relaxed = true)
			val mockContext = mockedContext(null, branchArm, sec1, sec2)

			val candidate = PathCandidate(sections = listOf(sec1, sec2), switchMovementCount = 1)

			val commands = PathCommandTranslator.translate("train1", zA, candidate, mockContext)

			val switchCmds = commands.filterIsInstance<DispatchDecision.SetSwitchPosition>()
			assertThat(switchCmds).isEmpty()

			val signalCmds = commands.filterIsInstance<DispatchDecision.SetSignalAspect>()
			assertThat(signalCmds).hasSize(1)
			assertThat(signalCmds[0].semaphoreName).isEqualTo(zA.name)
		}

		@Test
		@DisplayName("both segments null — SetSwitchPosition skipped, diagnostic renders both as null")
		fun bothSegmentsNullSkipSwitchCommand() {
			val sec1 = mockk<TrackSection>(relaxed = true)
			val sec2 = mockk<TrackSection>(relaxed = true)
			val mockContext = mockedContext(null, null, sec1, sec2)

			val candidate = PathCandidate(sections = listOf(sec1, sec2), switchMovementCount = 1)

			val commands = PathCommandTranslator.translate("train1", zA, candidate, mockContext)

			val switchCmds = commands.filterIsInstance<DispatchDecision.SetSwitchPosition>()
			assertThat(switchCmds).isEmpty()

			val signalCmds = commands.filterIsInstance<DispatchDecision.SetSignalAspect>()
			assertThat(signalCmds).hasSize(1)
			assertThat(signalCmds[0].semaphoreName).isEqualTo(zA.name)
		}

		@Test
		@DisplayName("to == null (undeterminable exit segment) — SetSwitchPosition skipped")
		fun toNullSkipsSwitchCommand() {
			// Covers the second half of the `from != null && to != null` guard: `from` resolves
			// but `to` does not.
			val (mainArm, _) = vAArmSegments()
			val sec1 = mockk<TrackSection>(relaxed = true)
			val sec2 = mockk<TrackSection>(relaxed = true)
			val mockContext = mockedContext(mainArm, null, sec1, sec2)

			val candidate = PathCandidate(sections = listOf(sec1, sec2), switchMovementCount = 1)

			val commands = PathCommandTranslator.translate("train1", zA, candidate, mockContext)

			val switchCmds = commands.filterIsInstance<DispatchDecision.SetSwitchPosition>()
			assertThat(switchCmds).isEmpty()

			val signalCmds = commands.filterIsInstance<DispatchDecision.SetSignalAspect>()
			assertThat(signalCmds).hasSize(1)
			assertThat(signalCmds[0].semaphoreName).isEqualTo(zA.name)
		}
	}
}
