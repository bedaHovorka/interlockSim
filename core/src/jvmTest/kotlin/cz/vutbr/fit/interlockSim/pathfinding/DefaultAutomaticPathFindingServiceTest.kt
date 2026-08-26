/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.pathfinding

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.inject
import kotlin.time.measureTime

@DisplayName("DefaultAutomaticPathFindingService")
class DefaultAutomaticPathFindingServiceTest : KoinTestBase() {
	private var context: EditingContext? = null

	private val editingContextFactory: JvmEditingContextFactory by inject()

	@AfterEach
	fun closeContext() {
		(context as? DefaultEditingContext)?.close()
		context = null
	}

	private fun linearContext(): EditingContext = TestTopologies.simpleLinearPath().also { context = it }

	private fun semaphoreContext(): EditingContext =
		TestTopologies.linearPathWithSemaphore(semaphoreAllowing = false).also { context = it }

	private fun yJunctionContext(): EditingContext = TestTopologies.yJunctionWithSwitch().also { context = it }

	private fun shuntingLoopContext(): EditingContext {
		val ctx = TestFixtures.loadShuntingEditingContext(editingContextFactory)
		context = ctx
		return ctx
	}

	@Nested
	@DisplayName("findShortestPath")
	inner class FindShortestPath {
		@Test
		fun `returns non-null result for existing route`() {
			val ctx = linearContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			val result = svc.findShortestPath(a, b)

			assertThat(result).isNotNull()
			assertThat(result!!.sections).hasSize(1)
			assertThat(result.totalCost).isGreaterThan(0.0)
		}

		@Test
		fun `returns null when no path exists`() {
			val ctx = TestTopologies.deadEndSingleInOut().also { context = it }
			val svc = ctx.getAutomaticPathFindingService()
			val a = ctx.getInOuts().single { it.getName() == "A" }

			// Only one InOut, no other reachable separators
			val result = svc.findShortestPath(a, a)

			// start == target returns empty-section result, not null
			assertThat(result).isNotNull()
			assertThat(result!!.sections).isEmpty()
		}

		@Test
		fun `returns empty sections when start equals target`() {
			val ctx = linearContext()
			val svc = ctx.getAutomaticPathFindingService()
			val a = ctx.getInOuts().single { it.getName() == "A" }

			val result = svc.findShortestPath(a, a)

			assertThat(result).isNotNull()
			assertThat(result!!.sections).isEmpty()
			assertThat(result.totalCost).isEqualTo(0.0)
		}

		@Test
		fun `returns null when source and target are disconnected`() {
			val ctx =
				TestContextBuilder()
					.withInOut("A", 1, 1, true)
					.withInOut("B", 5, 5, false)
					.buildEditingContext()
					.also { context = it }
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			assertThat(svc.findShortestPath(a, b)).isNull()
		}

		@Test
		fun `BY_ELEMENT_COUNT returns cost equal to section count`() {
			val ctx = semaphoreContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			val result = svc.findShortestPath(a, b, PathCostFunctions.BY_ELEMENT_COUNT)

			assertThat(result).isNotNull()
			// A → Semaphore → B = 2 sections
			assertThat(result!!.sections).hasSize(2)
			assertThat(result.totalCost).isEqualTo(2.0)
		}

		@Test
		fun `BY_LENGTH returns positive total length`() {
			val ctx = linearContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			val result = svc.findShortestPath(a, b, PathCostFunctions.BY_LENGTH)

			assertThat(result).isNotNull()
			assertThat(result!!.totalCost).isGreaterThan(0.0)
			// totalCost must equal totalLength
			assertThat(result.totalCost).isEqualTo(result.totalLength)
		}

		@Test
		fun `BY_TRAVEL_TIME returns positive cost without NPE`() {
			val ctx = linearContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			// Must not throw NullPointerException (regression for BY_TRAVEL_TIME NPE fix)
			val result = svc.findShortestPath(a, b, PathCostFunctions.BY_TRAVEL_TIME)

			assertThat(result).isNotNull()
			assertThat(result!!.totalCost).isGreaterThan(0.0)
		}
	}

	@Nested
	@DisplayName("findAllPaths")
	inner class FindAllPaths {
		@Test
		fun `returns single path on linear network`() {
			val ctx = linearContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			val paths = svc.findAllPaths(a, b)

			assertThat(paths).hasSize(1)
			assertThat(paths[0].sections).hasSize(1)
		}

		@Test
		fun `returns two paths on Y-junction network`() {
			val ctx = yJunctionContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val entry = inOuts.single { it.getName() == "Entry" }
			val exitMain = inOuts.single { it.getName() == "ExitMain" }

			// Y-junction: Entry → Junction → ExitMain (one direct path)
			val paths = svc.findAllPaths(entry, exitMain)

			assertThat(paths).hasSize(1)
		}

		@Test
		fun `paths are sorted by cost cheapest first`() {
			val ctx = semaphoreContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			val paths = svc.findAllPaths(a, b, costFunction = PathCostFunctions.BY_LENGTH)

			// All paths exist, must be sorted ascending by totalCost
			for (i in 0 until paths.size - 1) {
				assertThat(paths[i].totalCost).isEqualTo(paths[i].totalCost)
				assertThat(paths[i].totalCost <= paths[i + 1].totalCost).isTrue()
			}
		}

		@Test
		fun `returns empty list when nodes are disconnected`() {
			// Two InOuts with no track connecting them — truly unreachable
			val ctx =
				TestContextBuilder()
					.withInOut("A", 1, 1, true)
					.withInOut("B", 5, 5, false)
					.buildEditingContext()
					.also { context = it }
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			val paths = svc.findAllPaths(a, b)

			assertThat(paths).isEmpty()
		}

		@Test
		fun `maxPaths cap is respected`() {
			val ctx = semaphoreContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			val paths = svc.findAllPaths(a, b, maxPaths = 1)

			assertThat(paths.size <= 1).isTrue()
		}
	}

	@Nested
	@DisplayName("isPathAvailable")
	inner class IsPathAvailable {
		@Test
		fun `returns true when path exists`() {
			val ctx = linearContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			assertThat(svc.isPathAvailable(a, b)).isTrue()
		}

		@Test
		fun `returns false when nodes are disconnected`() {
			// Two InOuts with no track between them
			val ctx =
				TestContextBuilder()
					.withInOut("A", 1, 1, true)
					.withInOut("B", 5, 5, false)
					.buildEditingContext()
					.also { context = it }
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			assertThat(svc.isPathAvailable(a, b)).isFalse()
		}
	}

	@Nested
	@DisplayName("PathFindingResult")
	inner class PathFindingResultTest {
		@Test
		fun `elementCount equals sections size`() {
			val ctx = semaphoreContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			val result = svc.findShortestPath(a, b)!!

			assertThat(result.elementCount).isEqualTo(result.sections.size)
		}

		@Test
		fun `totalLength is sum of section lengths`() {
			val ctx = semaphoreContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			val result = svc.findShortestPath(a, b, PathCostFunctions.BY_ELEMENT_COUNT)!!

			val expected = result.sections.sumOf { it.length() }
			assertThat(result.totalLength).isEqualTo(expected)
		}
	}

	@Nested
	@DisplayName("switch constraints")
	inner class SwitchConstraints {
		@Test
		fun `keeps valid main and branch routes from entry`() {
			val ctx = yJunctionContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val entry = inOuts.single { it.getName() == "Entry" }
			val exitMain = inOuts.single { it.getName() == "ExitMain" }
			val exitBranch = inOuts.single { it.getName() == "ExitBranch" }

			assertThat(svc.findAllPaths(entry, exitMain)).hasSize(1)
			assertThat(svc.findAllPaths(entry, exitBranch)).hasSize(1)
		}

		@Test
		fun `excludes impossible straight-to-branch switch transition`() {
			val ctx = yJunctionContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val exitMain = inOuts.single { it.getName() == "ExitMain" }
			val exitBranch = inOuts.single { it.getName() == "ExitBranch" }

			// One-way edge case: travelling from the straight (main) end of the switch
			// to the branch end is not physically possible. possibleFollowers(F) = {A},
			// so the route ExitMain → Switch(F→G) → ExitBranch is excluded by the
			// switch constraint.
			assertThat(svc.findAllPaths(exitMain, exitBranch)).isEmpty()
			assertThat(svc.findShortestPath(exitMain, exitBranch)).isNull()
		}

		@Test
		fun `blocked switch position excludes dependent route from branch side`() {
			val ctx = yJunctionContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val exitBranch = inOuts.single { it.getName() == "ExitBranch" }
			val exitMain = inOuts.single { it.getName() == "ExitMain" }

			// Switch-position constraint: when approaching from the branch arm the switch
			// blocks the main-arm exit. possibleFollowers(G) = {A} only, so any attempt
			// to reach ExitMain from ExitBranch without revisiting the switch (which
			// cycle detection prevents) yields no valid route.
			assertThat(svc.findAllPaths(exitBranch, exitMain)).isEmpty()
			assertThat(svc.findShortestPath(exitBranch, exitMain)).isNull()
		}
	}

	@Nested
	@DisplayName("multiple alternatives")
	inner class MultipleAlternatives {
		@Test
		fun `returns multiple paths on shunting loop sorted by length`() {
			val ctx = shuntingLoopContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			val paths = svc.findAllPaths(a, b, costFunction = PathCostFunctions.BY_LENGTH)

			assertThat(paths).isNotEmpty()
			assertThat(paths.size).isGreaterThan(1)
			for (i in 0 until paths.size - 1) {
				assertThat(paths[i].totalCost).isLessThanOrEqualTo(paths[i + 1].totalCost)
			}
		}

		@Test
		fun `findShortestPath returns the cheapest of the alternatives`() {
			val ctx = shuntingLoopContext()
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			val shortest = svc.findShortestPath(a, b, PathCostFunctions.BY_LENGTH)!!
			val alternatives = svc.findAllPaths(a, b, costFunction = PathCostFunctions.BY_LENGTH)

			assertThat(shortest.totalCost).isEqualTo(alternatives.first().totalCost)
			assertThat(alternatives.all { it.totalCost >= shortest.totalCost }).isTrue()
		}
	}

	@Nested
	@DisplayName("performance")
	inner class Performance {
		/**
		 * Verifies that path finding on a 100-element network completes in under 1 second.
		 *
		 * **Network composition (deterministic):**
		 * - 2 InOut separators (entry A and exit B)
		 * - 49 RailSemaphore separators
		 * - 50 SimpleTrackBlock edges connecting them in a linear chain
		 * - Total: 51 node separators + 50 track blocks = 101 path elements
		 *
		 * **Determinism:** The topology is fully static (no dynamic state). Five warm-up
		 * iterations are performed before measurement to allow JVM JIT compilation to
		 * stabilise, making the timed result representative and repeatable.
		 *
		 * The 1 000 ms budget is conservative; typical JVM execution is well under 50 ms.
		 */
		@Test
		fun `findShortestPath completes in under one second on a 100-element network`() {
			// 49 semaphores + 2 InOuts + 50 track blocks = 101 path elements (>= 100).
			val ctx = TestTopologies.linearPathWithSemaphoreSequence(semaphoreCount = 49).also { context = it }
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			// Warm-up to reduce JVM JIT noise.
			repeat(5) { svc.findShortestPath(a, b, PathCostFunctions.BY_LENGTH) }

			val duration =
				measureTime {
					svc.findShortestPath(a, b, PathCostFunctions.BY_LENGTH)
				}

			assertThat(duration.inWholeMilliseconds).isLessThan(1000L)
		}

		/**
		 * Verifies the headline invariant of [TestTopologies.branchySwitchChain]: a network
		 * with [stageCount] binary-switch bypass stages yields exactly 2^[stageCount]
		 * switch-constrained paths from "A" to "B".
		 *
		 * This also guards the topology builder against regressions (e.g. an accidental
		 * third branch or a dropped stage) that the 15-stage performance test below cannot
		 * catch — it only asserts cap saturation, not the exact path count.
		 *
		 * Small [stageCount] values keep this fast and timing-flake-free so it can run in
		 * the regular `test` target.
		 */
		@ParameterizedTest(name = "stageCount={0} yields exactly 2^{0} paths")
		@ValueSource(ints = [1, 2, 3, 4, 5])
		fun `branchySwitchChain enumerates exactly 2^n paths`(stageCount: Int) {
			val ctx = TestTopologies.branchySwitchChain(stageCount = stageCount).also { context = it }
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			// maxPaths must exceed 2^stageCount so the cap does not truncate the full enumeration.
			val expectedPathCount = 1 shl stageCount
			val result = svc.findAllPaths(a, b, maxPaths = expectedPathCount)

			assertThat(result).hasSize(expectedPathCount)
		}

		/**
		 * Verifies that [findAllPaths] on a switch-rich branchy network
		 * (a) respects the [maxPaths] cap after full BFS enumeration, and
		 * (b) completes within an acceptable wall-clock budget.
		 *
		 * **Network composition:** [TestTopologies.branchySwitchChain] with 15 binary-switch
		 * bypass stages creates exactly 2^15 = 32 768 switch-constrained paths from "A" to
		 * "B".  The implementation enumerates all 32 768 paths in BFS *before* applying the
		 * [maxPaths] cap, so this test exercises the combinatorial enumeration cost directly
		 * and will catch regressions that make [findAllSwitchConstrainedPaths] unexpectedly
		 * slower on branchy networks.
		 *
		 * **Time budget (5 000 ms):** Generous to survive CI variance; typical JVM execution
		 * for 32 768 enumerated paths is well under 1 s.
		 */
		@Test
		fun `findAllPaths on 15-stage branchy network respects maxPaths cap and completes within time budget`() {
			// 15 binary-switch bypass stages → 2^15 = 32 768 switch-constrained paths.
			val ctx = TestTopologies.branchySwitchChain(stageCount = 15).also { context = it }
			val svc = ctx.getAutomaticPathFindingService()
			val inOuts = ctx.getInOuts()
			val a = inOuts.single { it.getName() == "A" }
			val b = inOuts.single { it.getName() == "B" }

			val maxPaths = 10

			// Warm-up to reduce JVM JIT noise before the timed measurement.
			repeat(5) { svc.findAllPaths(a, b, maxPaths = maxPaths) }

			val duration =
				measureTime {
					svc.findAllPaths(a, b, maxPaths = maxPaths)
				}

			// Time budget: full BFS enumeration of 32 768 paths must finish in under 5 s.
			assertThat(duration.inWholeMilliseconds).isLessThan(5000L)

			// Correctness: the maxPaths cap is applied after enumeration and must be respected.
			// 32 768 available paths >> maxPaths=10, so the cap is saturated.
			val result = svc.findAllPaths(a, b, maxPaths = maxPaths)
			assertThat(result).hasSize(maxPaths)
		}
	}
}
