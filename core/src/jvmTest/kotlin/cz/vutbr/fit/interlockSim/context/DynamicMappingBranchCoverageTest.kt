/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.cells.createConstantInstance
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.FakeSimulationController
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * A [DynamicPathSeparator] implementor that `collectUnmappedSeparators` does not know.
 *
 * The interface is not sealed, so the validator's `error` arm exists to catch exactly this
 * kind of implementor. It is not a `NodeCell`, so `initializeDynamicMapping` skips it.
 */
private class ForeignSeparator(
	delegate: DynamicPathSeparator
) : DynamicPathSeparator by delegate

/**
 * Branch coverage for `DefaultSimulationContext.initializeDynamicMapping` and
 * `collectUnmappedSeparators` (issue #1005, Task 41 ruling).
 *
 * In production every grid `NodeCell` is already replaced by a `Dynamic*` wrapper, so the
 * grid-pass `when` arms of `initializeDynamicMapping` never run. These tests reach them by
 * writing fresh static cells into the simulation grid and calling the `internal`
 * `initializeDynamicMapping()` directly. The validator tests go through `run()`, which
 * reaches `validateDynamicMapping()` before the simulation engine starts.
 */
@DisplayName("Dynamic mapping branch coverage")
class DynamicMappingBranchCoverageTest : KoinTestBase() {
	private val factory: SimulationContextFactory by inject()

	private fun loadVyhybna(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { factory.createContext(it) }.tracked() as DefaultSimulationContext

	private fun DefaultSimulationContext.grid(): DefaultRailWayNetGrid = getRailWayNetGrid() as DefaultRailWayNetGrid

	private fun DefaultSimulationContext.freePoints(): List<Point> {
		val grid = getRailWayNetGrid()
		val free = mutableListOf<Point>()
		for (x in 0 until grid.cols) {
			for (y in 0 until grid.rows) {
				if (grid.getCellAt(x, y) == null) free.add(Point(x, y))
			}
		}
		return free
	}

	/** Three free points in one row, left to right. */
	private fun DefaultSimulationContext.freeRowOfThree(): List<Point> {
		val free = freePoints().toSet()
		val start =
			free.first { p -> Point(p.x + 1, p.y) in free && Point(p.x + 2, p.y) in free }
		return listOf(start, Point(start.x + 1, start.y), Point(start.x + 2, start.y))
	}

	/**
	 * Two free points whose column-major order (x outer, y inner) differs from their
	 * row-major order: the first has the smaller x but the larger y.
	 */
	private fun DefaultSimulationContext.freePairColumnMajorNotRowMajor(): Pair<Point, Point> {
		val free = freePoints()
		for (first in free) {
			val second = free.firstOrNull { it.x > first.x && it.y < first.y }
			if (second != null) return first to second
		}
		error("vyhybna.xml grid has no free point pair with different column- and row-major order")
	}

	private fun failureMessageOfRun(context: DefaultSimulationContext): String {
		var message = ""
		assertFailure { context.run(FakeSimulationController()) }
			.isInstanceOf<IllegalStateException>()
			.given { message = it.message.orEmpty() }
		return message
	}

	private fun unmappedSeparatorsLine(message: String): String =
		message.lines().single { it.startsWith("Unmapped separators: ") }

	@Nested
	@DisplayName("initializeDynamicMapping - InOut arm and track pass")
	inner class InitializeDynamicMappingBranches {
		@Test
		@DisplayName("a static InOut in a fresh context is mapped with both semaphores and starts the InOut list")
		fun staticInOutInFreshContext_mapsSemaphoresAndStartsInOutList() {
			val context =
				DefaultSimulationContext(10, 10, DefaultSimulationProcessFactory()).tracked()
			val inOut = InOut("injected", true, Cell.SpatialType.HORIZONTAL)
			context.grid().put(Point(2, 3), inOut)

			context.initializeDynamicMapping()

			val dynamic = context.toDynamic(inOut) as DynamicInOut
			assertThat(dynamic.staticRef).isSameInstanceAs(inOut)
			assertThat(context.toDynamic(inOut.inSemaphore)).isSameInstanceAs(dynamic.inSemaphore)
			assertThat(context.toDynamic(inOut.outSemaphore)).isSameInstanceAs(dynamic.outSemaphore)
			// The static inouts list is empty, so only the list started in the InOut arm can hold it.
			val inOuts = context.getInOuts()
			assertThat(inOuts).hasSize(1)
			assertThat(inOuts.single()).isSameInstanceAs(dynamic)
		}

		@Test
		@DisplayName("two injected InOuts join the InOut list in column-major order")
		fun twoInjectedInOuts_joinInOutListInColumnMajorOrder() {
			val context = loadVyhybna()
			val loadedInOuts = context.getInOuts().toList()
			val (firstPoint, secondPoint) = context.freePairColumnMajorNotRowMajor()
			val first = InOut("injectedFirst", true, Cell.SpatialType.HORIZONTAL)
			val second = InOut("injectedSecond", true, Cell.SpatialType.HORIZONTAL)
			context.grid().put(secondPoint, second)
			context.grid().put(firstPoint, first)

			context.initializeDynamicMapping()

			val inOuts = context.getInOuts().toList()
			assertThat(inOuts).hasSize(loadedInOuts.size + 2)
			assertThat(inOuts.take(loadedInOuts.size)).isEqualTo(loadedInOuts)
			assertThat(inOuts.takeLast(2).map { it.staticRef }).containsExactly(first, second)
		}

		@Test
		@DisplayName("semaphores mapped earlier as grid cells keep their wrappers when the InOut is mapped")
		fun semaphoresMappedEarlier_keepTheirWrappers() {
			val context = loadVyhybna()
			val (inSemaphorePoint, outSemaphorePoint, inOutPoint) = context.freeRowOfThree()
			val inOut = InOut("injected", true, Cell.SpatialType.HORIZONTAL)
			context.grid().put(inSemaphorePoint, inOut.inSemaphore)
			context.grid().put(outSemaphorePoint, inOut.outSemaphore)
			context.grid().put(inOutPoint, inOut)

			context.initializeDynamicMapping()

			val dynamic = context.toDynamic(inOut) as DynamicInOut
			val inWrapper = context.toDynamic(inOut.inSemaphore) as DynamicRailSemaphore
			val outWrapper = context.toDynamic(inOut.outSemaphore) as DynamicRailSemaphore
			assertThat(inWrapper.staticRef).isSameInstanceAs(inOut.inSemaphore)
			assertThat(outWrapper.staticRef).isSameInstanceAs(inOut.outSemaphore)
			assertThat(inWrapper).isNotSameInstanceAs(dynamic.inSemaphore)
			assertThat(outWrapper).isNotSameInstanceAs(dynamic.outSemaphore)
		}

		@Test
		@DisplayName("a second call keeps the track wrapper identity for the static track and its alias")
		fun secondCall_keepsTrackWrapperIdentity() {
			val context = loadVyhybna()
			val blocks = context.getGraph().values().toList()
			val byStatic = blocks.map { context.toDynamic(it.staticRef as TrackFacility) }
			val byAlias = blocks.map { context.toDynamic(it) }
			assertThat(blocks.isNotEmpty()).isTrue()

			context.initializeDynamicMapping()

			blocks.forEachIndexed { index, block ->
				assertThat(byAlias[index]).isSameInstanceAs(byStatic[index])
				assertThat(context.toDynamic(block.staticRef as TrackFacility)).isSameInstanceAs(byStatic[index])
				assertThat(context.toDynamic(block)).isSameInstanceAs(byAlias[index])
			}
		}
	}

	@Nested
	@DisplayName("collectUnmappedSeparators - through run()")
	inner class CollectUnmappedSeparatorsBranches {
		@Test
		@DisplayName("an unmapped DynamicRailSwitch is reported with its position")
		fun unmappedDynamicRailSwitch_isReported() {
			val context = loadVyhybna()
			val point = context.freePoints().first()
			val switch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			context.grid().put(point, DynamicRailSwitch(switch))

			val message = failureMessageOfRun(context)

			assertThat(message.startsWith("Dynamic mapping incomplete!\n")).isTrue()
			assertThat(unmappedSeparatorsLine(message)).isEqualTo(
				"Unmapped separators: DynamicRailSwitch at (${point.x},${point.y}) - staticRef not mapped"
			)
			assertThat(message.lines().none { it.startsWith("Unmapped tracks: ") }).isTrue()
		}

		@Test
		@DisplayName("an unmapped DynamicInOut is reported with its position")
		fun unmappedDynamicInOut_isReported() {
			val context = loadVyhybna()
			val point = context.freePoints().first()
			val inOut = InOut("unmapped", true, Cell.SpatialType.HORIZONTAL)
			val dynamic =
				DynamicInOut(
					inOut,
					createDynamicInstance(inOut.inSemaphore),
					createConstantInstance(inOut.outSemaphore, Signal.FREE)
				)
			context.grid().put(point, dynamic)

			val message = failureMessageOfRun(context)

			assertThat(unmappedSeparatorsLine(message)).isEqualTo(
				"Unmapped separators: DynamicInOut at (${point.x},${point.y}) - staticRef not mapped"
			)
		}

		@Test
		@DisplayName("an unmapped DynamicRailSemaphore is reported with its position")
		fun unmappedDynamicRailSemaphore_isReported() {
			val context = loadVyhybna()
			val point = context.freePoints().first()
			context.grid().put(point, createDynamicInstance(RailSemaphore(true, Cell.SpatialType.HORIZONTAL)))

			val message = failureMessageOfRun(context)

			// createDynamicInstance returns the DynamicRailSemaphore subclass DefaultDynamicSemaphore.
			assertThat(unmappedSeparatorsLine(message)).isEqualTo(
				"Unmapped separators: DefaultDynamicSemaphore at (${point.x},${point.y}) - staticRef not mapped"
			)
		}

		@Test
		@DisplayName("an unknown DynamicPathSeparator implementor fails the validation")
		fun unknownDynamicPathSeparator_failsValidation() {
			val context = loadVyhybna()
			val point = context.freePoints().first()
			val delegate = createDynamicInstance(RailSemaphore(true, Cell.SpatialType.HORIZONTAL))
			context.grid().put(point, ForeignSeparator(delegate))

			assertFailure { context.run(FakeSimulationController()) }
				.isInstanceOf<IllegalStateException>()
				.hasMessage("Unknown DynamicPathSeparator type: ForeignSeparator")
		}

		@Test
		@DisplayName("a mapped static switch is not reported and entries keep column-major order")
		fun mappedStaticSwitchIsNotReported_entriesInColumnMajorOrder() {
			val context = loadVyhybna()
			val (firstPoint, secondPoint) = context.freePairColumnMajorNotRowMajor()
			val staticPoint = context.freePoints().first { it != firstPoint && it != secondPoint }
			context.grid().put(staticPoint, RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE))
			val firstSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			val secondSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			context.grid().put(secondPoint, DynamicRailSwitch(secondSwitch))
			context.grid().put(firstPoint, DynamicRailSwitch(firstSwitch))

			val message = failureMessageOfRun(context)

			assertThat(unmappedSeparatorsLine(message)).isEqualTo(
				"Unmapped separators: " +
					"DynamicRailSwitch at (${firstPoint.x},${firstPoint.y}) - staticRef not mapped, " +
					"DynamicRailSwitch at (${secondPoint.x},${secondPoint.y}) - staticRef not mapped"
			)
			assertThat(message).contains("\nSeparator map: ")
		}
	}
}
