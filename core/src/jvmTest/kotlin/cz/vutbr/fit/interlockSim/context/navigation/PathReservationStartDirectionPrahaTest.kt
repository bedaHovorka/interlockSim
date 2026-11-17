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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.withLogOf
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.component.get

/**
 * Issue #1378 (engine layer, traffic-simulation-expert ruling): a route must START at a signal
 * facing the train. `reservePath` screens every candidate whose first block lies behind the
 * semaphore START (Step 1.4, [RouteCandidateScreen.screenRearFacingStarts]) before anything is
 * reserved, so a switch-free reversal onto the train's own block is refused as
 * `GeometricallyImpossible` instead of granted.
 *
 * Runs on the Praha station asset (`praha-hlavni-nadrazi-station.xml`), where the defect was
 * measured: platform track `k6` is bounded by `S9` (west end, faces west, towards `N-Vys-1`) and
 * `S15` (east end). A train arriving from the south stands on `k6` at `S9`; `S9 -> S15` asks it to
 * reverse onto its own block.
 *
 * Extends [PathReservationServiceTestBase] with a Praha fixture override (Issue #1394): the network
 * comes from [loadTestContext] and the base's InOut-count assertion from [expectedInOutCount], so the
 * lookups, `occupy` and the log capture are the base's, not private copies.
 */
@Tag("integration-test")
class PathReservationStartDirectionPrahaTest : PathReservationServiceTestBase() {
	override fun loadTestContext(): DefaultSimulationContext =
		CommonTestFixtures.parseSimulationContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML, get())

	/** The Praha station asset has eleven InOuts, not the base's default two. */
	override val expectedInOutCount: Int = PRAHA_IN_OUTS

	@Test
	fun `a route from S9 back onto the train's own platform track is geometrically impossible`() {
		val k6 = blockBetween(S9, S15)
		occupy(k6, TRAIN)
		val s9 = findSemaphoreByName(S9)
		val pathInfoBefore = registry.getPathInfo(TRAIN)

		var summaries: List<String> = emptyList()
		lateinit var result: PathReservationService.ReservationResult
		withLogOf(DefaultPathReservationService::class.java.name) { serviceLog ->
			result = service.reservePath(TRAIN, s9, findSemaphoreByName(S15))
			summaries = serviceLog.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
		}

		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()
		val reason = (result as PathReservationService.ReservationResult.GeometricallyImpossible).reason
		assertThat(summaries.single(), name = "the one refusal summary")
			.startsWith("reservePath: refused '$TRAIN' $S9 -> $S15: ")
		assertThat(reason).contains("faces away")
		assertThat(reason).contains(N_VYS_1)
		assertThat(registry.getBlocks(TRAIN).filter { it != k6 }, name = "newly reserved blocks").isEmpty()
		assertThat(registry.getPathInfo(TRAIN), name = "PathInfo").isEqualTo(pathInfoBefore)
		assertThat(s9.signal).isEqualTo(Signal.STOP)
	}

	@Test
	fun `a cross-track reversal logs no per-candidate WARN and at most one summary`() {
		occupy(blockBetween(S2, S5), TRAIN)

		withLogOf(SwitchConfigurator::class.java.name) { switchLog ->
			withLogOf(DefaultPathReservationService::class.java.name) { serviceLog ->
				val result = service.reservePath(TRAIN, findSemaphoreByName(S2), findSemaphoreByName(S11))

				// Every candidate is permanent: eastward ones leave S2 from behind (Step 1.4), westward
				// ones end at S11 from behind (G8) or need a reversal no switch joins (Issue #742).
				assertThat(result).isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()
				assertThat(warnCount(switchLog), name = "SwitchConfigurator WARN lines").isEqualTo(0)
				assertThat(warnCount(serviceLog), name = "service WARN lines").isLessThanOrEqualTo(1)
			}
		}
	}

	@Test
	fun `a route from S9 towards the train's own exit is still granted and lights S9`() {
		occupy(blockBetween(S9, S15), TRAIN)
		val s9 = findSemaphoreByName(S9)

		val result = service.reservePath(TRAIN, s9, inOutNamed(N_VYS_1))

		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(s9.signal.isAllowing(), name = "S9 allowing").isTrue()
	}

	@Test
	fun `start-screened candidates mixed with a blocked forward one stay ordinary contention`() {
		occupy(blockBetween(S9, S15), TRAIN)
		occupy(blockBetween(SW24, S9), OTHER_TRAIN)

		var summary = ""
		lateinit var result: PathReservationService.ReservationResult
		withLogOf(DefaultPathReservationService::class.java.name) { serviceLog ->
			result = service.reservePath(TRAIN, findSemaphoreByName(S9), inOutNamed(N_VYS_1))
			summary = serviceLog.list.single { it.formattedMessage.startsWith(SUMMARY_PREFIX) }.formattedMessage
		}

		// S9 -> N-Vys-1 has candidates leaving S9 eastwards onto k6 (Step 1.4) and westwards over
		// SW24, which the other train blocks: a mix, so the request stays retryable contention.
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		val counts = SUMMARY_COUNTS.find(summary)
		assertThat(counts, name = "summary counts in: $summary").isNotNull()
		assertThat(counts!!.groupValues[1].toInt(), name = "geometric candidates").isGreaterThan(0)
		assertThat(counts.groupValues[2].toInt(), name = "blocked candidates").isGreaterThan(0)
		assertThat(summary, name = "first geometric reason").contains("(first: Route origin '$S9' faces away")
	}

	private fun warnCount(appender: ListAppender<ILoggingEvent>): Int = appender.list.count { it.level == Level.WARN }

	private companion object {
		const val PRAHA_IN_OUTS = 11
		const val TRAIN = "Train #2"
		const val OTHER_TRAIN = "Train #1"
		const val S2 = "S2"
		const val S5 = "S5"
		const val S9 = "S9"
		const val S11 = "S11"
		const val S15 = "S15"
		const val SW24 = "SW24"
		const val N_VYS_1 = "N-Vys-1"
		const val SUMMARY_PREFIX = "reservePath: refused '$TRAIN' $S9 -> $N_VYS_1: "
		val SUMMARY_COUNTS = Regex("""(\d+) geometric \(first: .*\), \d+ divergent, (\d+) blocked of""")
	}
}
