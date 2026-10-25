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
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import assertk.fail
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.testutil.separatorLabel
import cz.vutbr.fit.interlockSim.util.DynamicWrapperUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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
class ReservationTargetDiscoveryTest : PathReservationServiceTestBase() {
	private lateinit var query: ReservationTargetQuery

	/** Names to separators, one grid scan per test; the lookups below are map hits. */
	private lateinit var separatorsByName: Map<String, DynamicPathSeparator>

	@BeforeEach
	fun setUpFixtures() {
		query = simulationContext.getRoutingServices().getReservationTargetQuery()
		separatorsByName =
			simulationContext
				.cellsOfType<DynamicPathSeparator>()
				.associateBy { separatorLabel(it) }
	}

	/** The separator of the grid with the given name: a semaphore, an InOut or a switch. */
	private fun separator(name: String): DynamicPathSeparator = separatorsByName.getValue(name)

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
				assertThat(candidates.map { separatorLabel(it.separator) to it.available }, name = "candidates from $start")
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
			// Discovery takes an OrientedPathSeparator (Issue #1207): a switch has no order to pin.
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

	@Nested
	@DisplayName("ReservationTargetDiscovery directly")
	inner class Direct {
		private lateinit var discovery: ReservationTargetDiscovery

		@BeforeEach
		fun createDiscovery() {
			discovery = ReservationTargetDiscovery(simulationContext.scope.get(), simulationContext)
		}

		@Test
		@DisplayName("reservePathToAnyTargets keeps the recorded order; only InOuts carry precomputed paths")
		fun wholeNetworkTargetsFromZA() {
			val targets = discovery.reservePathToAnyTargets(separator("zA") as OrientedPathSeparator)

			assertThat(targets.map { separatorLabel(it.first) })
				.containsExactly("B", "A", "doA1", "doA2", "doB2", "doB1", "zB")
			assertThat(targets.filter { it.second != null }.map { separatorLabel(it.first) }).containsExactly("B", "A")
		}

		@Test
		@DisplayName("reservePathToAnyTargets from an InOut puts the opposite-side exit first")
		fun wholeNetworkTargetsFromInOut() {
			// Recorded: from each exit the far exit leads (opposite side), the start itself is
			// absent, and the semaphores follow in grid order.
			for ((start, expected) in mapOf(
				"A" to listOf("B", "zA", "doA1", "doA2", "doB2", "doB1", "zB"),
				"B" to listOf("A", "zA", "doA1", "doA2", "doB2", "doB1", "zB")
			)) {
				assertThat(
					discovery
						.reservePathToAnyTargets(separator(start) as OrientedPathSeparator)
						.map { separatorLabel(it.first) },
					name = "order from $start"
				).containsExactly(*expected.toTypedArray())
			}
		}

		@Test
		@DisplayName("findNextSemaphoresVia from zA reaches both loop tracks' far signals, k1 first")
		fun nextSeparatorsFromZA() {
			val zA = separator("zA") as OrientedPathSeparator
			val (start, next) = discovery.resolveForwardSection(zA, "test")!!

			assertThat(start).isSameInstanceAs(zA)
			assertThat(discovery.findNextSemaphoresVia(start, next).map { separatorLabel(it) })
				.containsExactly("doB1", "doB2")
		}

		@Test
		@DisplayName("findNextSemaphoresVia puts the InOut before the opposing loop signal")
		fun nextSeparatorsPutInOutFirst() {
			val doB1 = separator("doB1") as OrientedPathSeparator
			val (start, next) = discovery.resolveForwardSection(doB1, "test")!!

			assertThat(discovery.findNextSemaphoresVia(start, next).map { separatorLabel(it) })
				.containsExactly("B", "doB2")
		}

		@Test
		@DisplayName("candidate query equals the service's, separator for separator")
		fun candidatesMatchService() {
			for (name in listOf("zA", "doA1", "doA2", "doB2", "doB1", "zB", "A", "B")) {
				val start = separator(name) as OrientedPathSeparator
				assertThat(discovery.findReservationTargetCandidates(start, null), name = "from $name")
					.isEqualTo(query.findReservationTargetCandidates(start, null))
			}
		}

		@Test
		@DisplayName("no forward section means nothing is available")
		fun nullNextIsUnavailable() {
			assertThat(discovery.isAnyNextTargetAvailable(separator("zA"), null)).isFalse()
		}

		@Test
		@DisplayName("candidates evaluate the same when the querying train owns no blocks")
		fun ownerBlocksCountAsFree() {
			val zA = separator("zA") as OrientedPathSeparator

			// Issue #1060: blocks owned by ownerTrainId count as free; with no reservation the
			// verdict list is therefore the owner-less one.
			assertThat(discovery.findReservationTargetCandidates(zA, "owner-train"))
				.isEqualTo(discovery.findReservationTargetCandidates(zA, null))
		}

		@Test
		@DisplayName("blocks owned by the querying train count as free (Issue #1060)")
		fun ownedBlocksCountAsFree() {
			val b = separator("B")
			val a = separator("A")

			try {
				assertThat(service.reservePathToAny("train-owner", separator("zA")))
					.isInstanceOf(PathReservationService.ReservationResult.Success::class)

				// B→A crosses the just-reserved route: available to its owner, not to another train.
				assertThat(
					discovery.isPathAvailableFor(b, a, DEFAULT_MAX_PATH_DEPTH, "train-owner"),
					name = "B to A for the owner"
				).isTrue()
				assertThat(
					discovery.isPathAvailableFor(b, a, DEFAULT_MAX_PATH_DEPTH, null),
					name = "B to A for another train"
				).isFalse()
			} finally {
				service.releasePath("train-owner")
			}
		}

		@Test
		@DisplayName("a static InOut resolves its forward section to the dynamic wrapper")
		fun staticInOutForwardSectionResolves() {
			val dynamicB = separator("B")
			val staticInOutB = DynamicWrapperUtils.unwrapToStatic(dynamicB) as OrientedPathSeparator
			val bBoundaryBlock =
				simulationContext
					.getGraph()
					.assignedEdges(simulationContext.getRailWayNetGrid().getLocation(dynamicB)!!)
					.values
					.first()

			val (start, section) = discovery.resolveForwardSection(staticInOutB, "test")!!

			assertThat(start).isSameInstanceAs(dynamicB)
			assertThat(section).isSameInstanceAs(bBoundaryBlock)
		}

		@Test
		@DisplayName("a next section not connected to the start throws")
		fun unrelatedNextThrows() {
			val zA = separator("zA") as OrientedPathSeparator
			val inOutB = simulationContext.toDynamic(simulationContext.getInOuts().toList()[0]) as DynamicInOut
			val bBoundaryBlock =
				simulationContext
					.getGraph()
					.assignedEdges(simulationContext.getRailWayNetGrid().getLocation(inOutB)!!)
					.values
					.first()

			val thrown =
				runCatching { discovery.findNextSemaphoresVia(zA, bBoundaryBlock) }
					.exceptionOrNull()
					?: fail("the unconnected next block did not throw")

			assertThat(thrown).isInstanceOf(IllegalStateException::class)
			assertThat(thrown.message ?: "", name = "throw reason").startsWith("No connection")
		}

		@Test
		@DisplayName("the availability probe walks a behind-block: from zA through its exit-side block")
		fun backwardSectionStillEnumerates() {
			val zA = separator("zA") as OrientedPathSeparator
			val forwardSection = discovery.resolveForwardSection(zA, "test")!!.second
			val backwardBlock =
				simulationContext
					.getGraph()
					.assignedEdges(simulationContext.getRailWayNetGrid().getLocation(zA)!!)
					.values
					.last { it != forwardSection }

			// The enumeration is directionless: the InOut A endpoint ends the walk and, free, makes
			// the probe true. The whole-network order below is not affected by the section choice.
			assertThat(discovery.isAnyNextTargetAvailable(zA, backwardBlock)).isTrue()
		}

		@Test
		@DisplayName("enumerating from an InOut collects the semaphore behind it")
		fun fromInOutCollectsBackwardSemaphore() {
			val a = separator("A")
			val ablock =
				simulationContext
					.getGraph()
					.assignedEdges(simulationContext.getRailWayNetGrid().getLocation(a)!!)
					.values
					.single()

			assertThat(discovery.findNextSemaphoresVia(a, ablock)).isEqualTo(listOf(separator("zA")))
		}

		@Test
		@DisplayName("the candidate verdicts are identical at TRACE level")
		fun enumerationTracesUnderTraceLevel() {
			val loggerByName = LoggerFactory.getLogger(ReservationTargetDiscovery::class.java.name) as Logger
			val expected =
				listOf("zA", "doA1", "doA2", "doB2", "doB1", "zB", "A", "B").associateWith { name ->
					discovery.findReservationTargetCandidates(separator(name) as OrientedPathSeparator, null)
				}
			val savedLevel = loggerByName.level
			loggerByName.level = Level.TRACE
			try {
				for ((start, expect) in expected) {
					assertThat(
						discovery.findReservationTargetCandidates(separator(start) as OrientedPathSeparator, null),
						name = "candidates from $start"
					).isEqualTo(expect)
				}
				val (sweepStart, sweepSection) =
					discovery
						.resolveForwardSection(separator("zA") as OrientedPathSeparator, "sweep")!!
				assertThat(discovery.isAnyNextTargetAvailable(sweepStart, sweepSection)).isTrue()
			} finally {
				loggerByName.level = savedLevel
			}
		}
	}

	private companion object {
		/** The name inside a dynamic separator's `toString()` (`Dynamic[zA, signal=STOP]`). */
		val NAME_IN_TO_STRING = Regex("""Dynamic\[([^,\]]+)""")

		/** Same forward segment `resolveForwardSection` derives (InOut: its track connection). */
		fun forwardSegment(start: OrientedPathSeparator) =
			when (start) {
				is DynamicInOut -> start.getTrackConnectionDirection()
				else -> start.direction()
			}
	}
}
