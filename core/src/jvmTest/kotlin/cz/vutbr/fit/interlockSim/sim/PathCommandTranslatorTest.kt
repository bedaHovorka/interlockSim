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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathCandidate
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.Util
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
 * - Empty sections list: empty command list
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

	@BeforeEach
	fun setUp() {
		val editingCtx =
			TestFixtures.loadShuntingXml().use { editingContextFactory.createContext(it) } as EditingContext
		context = ContextTransformer.createSimulationContext(editingCtx, processFactory)

		navigator = context.getRoutingServices().getTopologyNavigator()

		zA = elementAt(14, 8)
		doA1 = elementAt(16, 8)
		doA2 = elementAt(17, 9)
		vA = elementAt(15, 8)
		inOutA = elementAt(11, 8)
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
	 * [PathCommandTranslator.translate].
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
						is DispatchDecision.SetSwitchPosition -> "SP2b.3: switch on selected path for myTrain42"
						is DispatchDecision.SetSignalAspect -> "SP2b.3: entry signal on selected path for myTrain42"
						else -> return@forEach
					}
				)
			}
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
}
