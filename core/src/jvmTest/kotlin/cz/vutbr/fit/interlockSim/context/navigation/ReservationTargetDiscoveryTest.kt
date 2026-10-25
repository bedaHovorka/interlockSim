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
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.fail
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Pins the target order of the reservation target discovery on `vyhybna.xml` (Issue #1171,
 * SP12.5): the next-separator enumeration one section ahead (`findNextSemaphoresVia`, read
 * through [ReservationTargetQuery.findReservationTargetCandidates]) and the whole-network target
 * order `reservePathToAny` tries (InOuts first, opposite side first, shortest first, then every
 * semaphore in grid order).
 *
 * The expected orders were recorded on the unextracted service (goal-1B 9953beb4) before the
 * discovery moved out of [DefaultPathReservationService]; the extraction must not change them.
 *
 * Grid order of the semaphores on `vyhybna.xml`: zA, doA1, doA2, doB2, doB1, zB. The station
 * exits are A (west) and B (east); the switches are vA and vB.
 *
 * @since Issue #1171
 */
@DisplayName("Reservation target discovery order (Issue #1171)")
@Timeout(30, unit = TimeUnit.SECONDS)
class ReservationTargetDiscoveryTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var service: PathReservationService
	private lateinit var query: ReservationTargetQuery

	@BeforeEach
	fun setUp() {
		simulationContext =
			TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory).tracked()
		service = simulationContext.getRoutingServices().getPathReservationService()
		query = simulationContext.getRoutingServices().getReservationTargetQuery()
	}

	/** Every separator of the grid with the given name: a semaphore, an InOut or a switch. */
	private fun separator(name: String): DynamicPathSeparator =
		(
			simulationContext.cellsOfType<DynamicRailSemaphore>() +
				simulationContext.cellsOfType<DynamicInOut>() +
				simulationContext.cellsOfType<DynamicRailSwitch>()
		).single { label(it) == name }

	@Nested
	@DisplayName("next-separator enumeration (findNextSemaphoresVia)")
	inner class NextSeparators {
		/** Candidate names and verdicts on a free network, recorded before the extraction. */
		private val expectedCandidates =
			mapOf(
				"zA" to listOf("doB1" to true, "doB2" to true),
				"doA1" to listOf("A" to true, "doA2" to false),
				"doA2" to listOf("A" to true, "doA1" to false),
				"doB2" to listOf("B" to true, "doB1" to false),
				"doB1" to listOf("B" to true, "doB2" to false),
				"zB" to listOf("doA1" to true, "doA2" to true),
				"A" to listOf("zA" to true),
				"B" to listOf("zB" to true)
			)

		@Test
		@DisplayName("candidates from every oriented separator keep the recorded order and verdicts")
		fun candidateOrderIsPinned() {
			for ((start, expected) in expectedCandidates) {
				val candidates =
					query.findReservationTargetCandidates(separator(start) as OrientedPathSeparator, null)
				assertThat(candidates.map { label(it.separator) to it.available }, name = "candidates from $start")
					.isEqualTo(expected)
			}
		}

		@Test
		@DisplayName("isPathToAnyNextSemaphoreAvailable sees the same enumeration on a free network")
		fun anyNextSemaphoreAvailableFromEveryStart() {
			for (start in expectedCandidates.keys) {
				val oriented = separator(start) as OrientedPathSeparator
				val next =
					simulationContext.getGraph().assignedEdges(
						simulationContext.getRailWayNetGrid().getLocation(oriented)!!
					)[forwardSegment(oriented)]
				assertThat(service.isPathToAnyNextSemaphoreAvailable(oriented, next), name = "from $start")
					.isEqualTo(true)
			}
		}
	}

	@Nested
	@DisplayName("whole-network target order (reservePathToAny)")
	inner class WholeNetworkOrder {
		private lateinit var appender: ListAppender<ILoggingEvent>

		/** The service logger's own level, restored after each test (null = inherited). */
		private var savedLevel: Level? = null

		private val serviceLogger
			get() = LoggerFactory.getLogger(DefaultPathReservationService::class.java.name) as Logger

		@BeforeEach
		fun attachAppender() {
			appender = ListAppender<ILoggingEvent>().also { it.start() }
			// The pin reads a DEBUG line, so it must not depend on logback-test.xml's level for it.
			savedLevel = serviceLogger.level
			serviceLogger.level = Level.DEBUG
			serviceLogger.addAppender(appender)
		}

		@AfterEach
		fun detachAppender() {
			serviceLogger.detachAppender(appender)
			serviceLogger.level = savedLevel
		}

		/** Target order `reservePathToAny` logged from each start, recorded before the extraction. */
		private val expectedOrder =
			mapOf(
				"zA" to listOf("B", "A", "doA1", "doA2", "doB2", "doB1", "zB"),
				"doA1" to listOf("A", "B", "zA", "doA2", "doB2", "doB1", "zB"),
				"doA2" to listOf("A", "B", "zA", "doA1", "doB2", "doB1", "zB"),
				"doB2" to listOf("B", "A", "zA", "doA1", "doA2", "doB1", "zB"),
				"doB1" to listOf("B", "A", "zA", "doA1", "doA2", "doB2", "zB"),
				"zB" to listOf("A", "B", "zA", "doA1", "doA2", "doB2", "doB1"),
				"A" to listOf("B", "zA", "doA1", "doA2", "doB2", "doB1", "zB"),
				"B" to listOf("A", "zA", "doA1", "doA2", "doB2", "doB1", "zB")
			)

		/** Logged target order of the last `reservePathToAny` call, as separator names. */
		private fun loggedTargetOrder(): List<String> {
			val line =
				appender.list.map { it.formattedMessage }.lastOrNull { "Target order" in it }
					?: fail("no Target order line logged")
			return NAME_IN_TO_STRING.findAll(line).map { it.groupValues[1] }.toList()
		}

		@Test
		@DisplayName("every oriented start tries the recorded order and reserves its first target")
		fun orientedStartOrderIsPinned() {
			for ((start, expected) in expectedOrder) {
				appender.list.clear()
				val result = service.reservePathToAny("t1", separator(start))
				assertThat(loggedTargetOrder(), name = "order from $start").isEqualTo(expected)
				assertThat(result, name = "result from $start")
					.isInstanceOf(PathReservationService.ReservationResult.Success::class)
				service.releasePath("t1")
			}
		}

		@Test
		@DisplayName("a switch start is refused by the Issue #1188 guard before any target is discovered")
		fun switchStartIsRefusedBeforeDiscovery() {
			// The #1188 guard (fc84d96b) refuses a non-oriented start before STEP 1, so on this base
			// no orientation-free order can be recorded pre-extraction; the order is pinned directly
			// on the extracted class in the next commit instead.
			for (start in listOf("vA", "vB")) {
				appender.list.clear()
				val result = service.reservePathToAny("train-$start", separator(start))
				assertThat(result, name = "result from $start")
					.isInstanceOf(PathReservationService.ReservationResult.GeometricallyImpossible::class)
				assertThat(
					appender.list.any { "Target order" in it.formattedMessage },
					name = "target order logged for $start"
				).isFalse()
			}
		}
	}

	private companion object {
		/** The name inside a dynamic separator's `toString()` (`Dynamic[zA, signal=STOP]`). */
		val NAME_IN_TO_STRING = Regex("""Dynamic\[([^,\]]+)""")

		fun label(separator: DynamicPathSeparator): String =
			when (separator) {
				is DynamicRailSemaphore -> separator.name
				is DynamicInOut -> separator.name
				is DynamicRailSwitch -> separator.name
				else -> separator.toString()
			}

		/** Same forward segment `resolveForwardSection` derives (InOut: its track connection). */
		fun forwardSegment(start: OrientedPathSeparator) =
			when (start) {
				is DynamicInOut -> start.getTrackConnectionDirection()
				else -> start.direction()
			}
	}
}
