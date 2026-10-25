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
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import io.mockk.every
import io.mockk.spyk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The Step 2i merge fail-safe (Issue #904): when [PathReservationRegistry.registerPathInfo]
 * reports [PathReservationRegistry.MergeOutcome.Aborted] the candidate that reached the final
 * commit step blocks-reserved, switches locked, START signal lit — must be released
 * transactionally (exactly what it acquired), and
 * [DefaultPathReservationService.finalizePreparedCandidate] must report `null` so
 * [DefaultPathReservationService.reservePath] tries the next candidate.
 *
 * The registry-level abort causes (duplicated new-start, reversal, cycle) are pinned by
 * [PathReservationRegistryMergingTest] / [PathInfoMergerTest]; this file pins the service's
 * reaction. The abort is forced on a spy of the real registry so the rollback itself runs
 * against real registry state (the #904 fix made the natural scenario rare in production; the
 * fail-safe guards remain).
 *
 * Division of labour at this level: [PathReservationMergeAbortResourceReleaseTest] pins the
 * natural seeded-target abort, and [MergeAbortNeverThrowsTest] the extension shape that #904's
 * fix turned into a real merge; both are integration-tagged, this one is the spy-forced abort
 * inside the default untagged unit gate.
 *
 * Runs on `vyhybna.xml` through [PathReservationServiceTestBase].
 */
@DisplayName("reservePath: a candidate whose PathInfo merge aborts is released transactionally")
class PathReservationMergeAbortRollbackTest : PathReservationServiceTestBase() {
	private lateinit var zA: DynamicRailSemaphore
	private lateinit var doB1: DynamicPathSeparator
	private lateinit var vA: DynamicRailSwitch

	@BeforeEach
	fun setUpSeparators() {
		zA = findSemaphoreByName("zA")
		doB1 = findSemaphoreByName("doB1")
		vA = findSwitchByName("vA")
		if (vA.conf != Conf.MAIN) {
			vA.changeConf()
		}
	}

	/**
	 * [DefaultPathReservationService] onto a spy of the real [PathReservationRegistry]; only
	 * `registerPathInfo` is forced to report `Aborted` (with the would-be-registered candidate as
	 * its payload, like the real merger does), every other call passes through, so the test
	 * observes the true rollback of a genuinely aborted merge.
	 */
	private fun forcedAbortService(): DefaultPathReservationService {
		val spyRegistry = spyk(registry)
		every { spyRegistry.registerPathInfo(TRAIN, any()) } answers {
			PathReservationRegistry.MergeOutcome.Aborted(
				secondArg<PathInfo>(),
				"unit-test forced abort (not a real merger cause)"
			)
		}
		return DefaultPathReservationService(
			navigator = navigator,
			environment = environment,
			registry = spyRegistry,
			pathInfoBuilder = pathInfoBuilder,
			routeFinder = routeFinder
		)
	}

	@Test
	fun `a candidate whose PathInfo registration aborts is released and the attempt reports AllPathsBlocked`() {
		val result = forcedAbortService().reservePath(TRAIN, zA, doB1)

		assertThat(
			result,
			name = "the only candidate's merge aborted; an exhausted attempt reports contention"
		).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()

		assertThat(registry.getBlocks(TRAIN), name = "candidate blocks released").isEmpty()
		assertThat(registry.getPathInfo(TRAIN), name = "nothing was registered").isNull()
		assertThat(registry.getSwitchOwner(vA)).isNull()
		assertThat(vA.locked, name = "candidate switch released").isFalse()
		assertThat(zA.signal.isAllowing(), name = "START signal reset by the abort rollback").isFalse()
	}

	private companion object {
		const val TRAIN = "train1"
	}
}
