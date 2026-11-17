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
import assertk.assertions.isEqualTo
import assertk.assertions.single
import assertk.assertions.startsWith
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.component.get
import org.slf4j.LoggerFactory

/**
 * Issue #1393: the `NoPathExists` exit of `reservePath` (no candidate path at all) logs one
 * `reservePath: refused ...` line, at WARN, in the same family as the summary of a call that
 * exhausts its candidates. Before, that exit returned with no line, so a consumer counting the
 * refusal lines under-counted it.
 */
@Tag("integration-test")
class PathReservationNoPathRefusalLineTest : CommonKoinTestBase() {
	@Test
	fun `a semaphore request with no topological path logs one refusal line at WARN`() {
		val ctx = CommonTestFixtures.parseSimulationContext(DISCONNECTED_SEMAPHORE_XML, get()).tracked()
		val service = ctx.getRoutingServices().getPathReservationService()
		val start = ctx.cellsOfType<DynamicRailSemaphore>().single { it.name == S1 }

		val (result, lines) =
			refusalLinesOf { service.reservePath(TRAIN, start, inOut(ctx, B2)) }

		assertThat(result).isEqualTo(PathReservationService.ReservationResult.NoPathExists)
		assertThat(lines).single().given { event ->
			assertThat(event.level, name = "level").isEqualTo(Level.WARN)
			assertThat(event.formattedMessage).startsWith("reservePath: refused '$TRAIN' $S1 -> $B2: ")
			assertThat(event.formattedMessage).contains("no topological path")
		}
	}

	@Test
	fun `an InOut pair with no route logs one refusal line at WARN`() {
		val ctx = CommonTestFixtures.parseSimulationContext(NetworkResources.TWO_TRACKS_PARALLEL_XML, get()).tracked()
		val service = ctx.getRoutingServices().getPathReservationService()

		val (result, lines) =
			refusalLinesOf { service.reservePath(TRAIN, inOut(ctx, A1), inOut(ctx, B2)) }

		assertThat(result).isEqualTo(PathReservationService.ReservationResult.NoPathExists)
		assertThat(lines).single().given { event ->
			assertThat(event.level, name = "level").isEqualTo(Level.WARN)
			assertThat(event.formattedMessage).startsWith("reservePath: refused '$TRAIN' $A1 -> $B2: ")
		}
	}

	private fun inOut(
		ctx: DefaultSimulationContext,
		name: String
	): DynamicInOut =
		ctx
			.getInOuts()
			.map { ctx.toDynamic(it) }
			.filterIsInstance<DynamicInOut>()
			.single { it.name == name }

	/** Runs [body] and returns its result with the service's `reservePath: refused` lines. */
	private fun refusalLinesOf(
		body: () -> PathReservationService.ReservationResult
	): Pair<PathReservationService.ReservationResult, List<ILoggingEvent>> {
		val logger = LoggerFactory.getLogger(DefaultPathReservationService::class.java.name) as Logger
		val appender = ListAppender<ILoggingEvent>().also { it.start() }
		val levelBefore = logger.level
		logger.level = Level.DEBUG
		logger.addAppender(appender)
		try {
			val result = body()
			return result to appender.list.filter { it.formattedMessage.startsWith("reservePath: refused") }
		} finally {
			logger.detachAppender(appender)
			logger.level = levelBefore
		}
	}

	private companion object {
		const val TRAIN = "t1"
		const val S1 = "S1"
		const val A1 = "A1"
		const val B2 = "B2"

		/**
		 * Two tracks with no connection between them: `A1 - S1 - B1` and `A2 - B2`. No path of any
		 * kind leads from `S1` to `B2`.
		 */
		val DISCONNECTED_SEMAPHORE_XML =
			"""
			<?xml version="1.0"?>
			<!DOCTYPE net>
			<net X="100" Y="100">
				<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="false" name="A1"/>
				<RailSemaphore X="15" Y="10" SpatialType="HORIZONTAL" orientation="true" name="S1"/>
				<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="true" name="B1"/>
				<InOut X="10" Y="12" SpatialType="HORIZONTAL" orientation="false" name="A2"/>
				<InOut X="20" Y="12" SpatialType="HORIZONTAL" orientation="true" name="B2"/>
				<SimpleTrackBlock fromX="10" fromY="10" toX="15" toY="10" fromSegment="F" toSegment="A" length="50.0" maxSpeedfrom="25.0" maxSpeedto="25.0"/>
				<SimpleTrackBlock fromX="15" fromY="10" toX="20" toY="10" fromSegment="F" toSegment="A" length="50.0" maxSpeedfrom="25.0" maxSpeedto="25.0"/>
				<SimpleTrackBlock fromX="10" fromY="12" toX="20" toY="12" fromSegment="F" toSegment="A" length="100.0" maxSpeedfrom="30.0" maxSpeedto="30.0"/>
			</net>
			""".trimIndent()
	}
}
