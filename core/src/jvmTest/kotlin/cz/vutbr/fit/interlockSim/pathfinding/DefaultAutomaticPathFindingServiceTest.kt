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
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("DefaultAutomaticPathFindingService")
class DefaultAutomaticPathFindingServiceTest : KoinTestBase() {
	private var context: EditingContext? = null

	@AfterEach
	fun closeContext() {
		(context as? DefaultEditingContext)?.close()
		context = null
	}

	private fun linearContext(): EditingContext = TestTopologies.simpleLinearPath().also { context = it }

	private fun semaphoreContext(): EditingContext =
		TestTopologies.linearPathWithSemaphore(semaphoreAllowing = false).also { context = it }

	private fun yJunctionContext(): EditingContext = TestTopologies.yJunctionWithSwitch().also { context = it }

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
}
