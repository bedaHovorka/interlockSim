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
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.testutil.warnMessages
import cz.vutbr.fit.interlockSim.testutil.withLogOf
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

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
 * Extends [PrahaPathReservationTestBase] (the Praha fixture override of Issue #1394, shared since the
 * Issue #1385 review round), so the lookups, `occupy` and the log capture are the base's, not private
 * copies.
 */
@Tag("integration-test")
class PathReservationStartDirectionPrahaTest : PrahaPathReservationTestBase() {
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
			summaries = serviceLog.warnMessages()
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

				// Issue #1385: S2 -> S11 has no switch-legal route. The switch-blind listing had 20 480
				// candidates, every one permanent (Step 1.4, G8 or a #742 reversal no switch joins), and the
				// request ended GeometricallyImpossible; the switch-constrained enumeration lists none, so it
				// ends NoPathExists with its one refusal line and no candidate ever reaches a switch.
				assertThat(result).isInstanceOf<PathReservationService.ReservationResult.NoPathExists>()
				assertThat(switchLog.warnMessages(), name = "SwitchConfigurator WARN lines").isEmpty()
				assertThat(serviceLog.warnMessages().size, name = "service WARN lines").isEqualTo(1)
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
			result = reservePathOverSwitchBlindCandidates(TRAIN, findSemaphoreByName(S9), inOutNamed(N_VYS_1))
			summary = serviceLog.list.single { it.formattedMessage.startsWith(SUMMARY_PREFIX) }.formattedMessage
		}

		// S9 -> N-Vys-1 has switch-blind candidates leaving S9 eastwards onto k6 (Step 1.4) and westwards
		// over SW24, which the other train blocks: a mix, so the request stays retryable contention. Since
		// Issue #1385 the public request enumerates only the switch-legal westward ones (the eastward
		// ones reverse through a switch), so the mix is fed through the caller-supplied-paths overload.
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		val counts = SUMMARY_COUNTS.find(summary)
		assertThat(counts, name = "summary counts in: $summary").isNotNull()
		assertThat(counts!!.groupValues[1].toInt(), name = "geometric candidates").isGreaterThan(0)
		assertThat(counts.groupValues[2].toInt(), name = "blocked candidates").isGreaterThan(0)
		assertThat(summary, name = "first geometric reason").contains("(first: Route origin '$S9' faces away")
	}

	private companion object {
		const val OTHER_TRAIN = "Train #1"
		const val SUMMARY_PREFIX = "reservePath: refused '$TRAIN' $S9 -> $N_VYS_1: "
		val SUMMARY_COUNTS = Regex("""(\d+) geometric \(first: .*\), \d+ divergent, (\d+) blocked of""")
	}
}
