/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.single
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.OpenContention
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.sim.TrainJourneyEvent
import cz.vutbr.fit.interlockSim.sim.emitTrainJourneyEvent
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * A two-train contention inside a running kernel (Issue #1305, Goal 6 SP7): `train2` asks for the
 * route `train1` holds at [REFUSED_AT] and gets it at [RELEASED_AT], when `train1` releases. The
 * open-contention snapshot, the metrics' published copy and the journey record's blocked time are read
 * as values (counter-based evidence), never from a log.
 */
@Tag("integration-test")
class TrainWaitContentionRunTest : KoinTestBase() {
	private lateinit var context: DefaultSimulationContext

	private val service: PathReservationService
		get() = context.getRoutingServices().getPathReservationService()

	private val metrics: MetricsCollectionService
		get() = context.getMetricsServices().getMetricsCollectionService()

	private fun inOut(name: String): DynamicPathSeparator =
		context.toDynamic(context.getInOuts().single { it.name == name })

	/** What the scripted process saw at [OBSERVED_AT], while `train2` waited. */
	private var seenOpen: List<OpenContention> = emptyList()
	private var seenPublished: List<OpenContention> = emptyList()

	/**
	 * `train1` reserves A to B at 0 s, `train2` is refused at [REFUSED_AT], the process looks at
	 * [OBSERVED_AT], then at [RELEASED_AT] [releaseAtTheEnd] decides what happens; the process ends at
	 * [END_AT].
	 */
	private fun runScript(releaseAtTheEnd: (PathReservationService) -> Unit) {
		context = loadVyhybnaContext(warmUpDynamicWrappers = true)
		metrics // resolve the scoped service before run(), so it subscribes
		context.setMainProcess(
			object : LoopProcess() {
				override suspend fun iteration() {
					admit("train1")
					assertReservationSuccess(service.reservePath("train1", inOut("A"), inOut("B")))
					hold(REFUSED_AT)
					admit("train2")
					assertThat(service.reservePath("train2", inOut("A"), inOut("B")))
						.transform { it::class }
						.isEqualTo(PathReservationService.ReservationResult.AllPathsBlocked::class)
					hold(OBSERVED_AT - REFUSED_AT)
					seenOpen = service.openContentions()
					seenPublished = metrics.latestOpenContentions()
					hold(RELEASED_AT - OBSERVED_AT)
					releaseAtTheEnd(service)
					hold(END_AT - RELEASED_AT)
					terminate()
				}

				private fun admit(trainId: String) {
					emitTrainJourneyEvent(TrainJourneyEvent.Queued(trainId, "A", "B", time()))
					emitTrainJourneyEvent(TrainJourneyEvent.Admitted(trainId, time()))
				}
			}
		)
		context.run()
	}

	private fun blockedSecondsOf(trainId: String): Double =
		metrics.journeys().single { it.trainId == trainId }.blockedSeconds

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `a two-train contention is one open interval with the owner and lasts from refusal to release`() {
		runScript { s ->
			s.releasePath("train1")
			assertReservationSuccess(s.reservePath("train2", inOut("A"), inOut("B")))
		}

		assertThat(seenOpen.map { Triple(it.trainId, it.owner, it.since) })
			.containsExactly(Triple("train2", "train1", REFUSED_AT))
		// Published by the conflict event although no block event came after the refusal.
		assertThat(seenPublished).isEqualTo(seenOpen)
		assertThat(blockedSecondsOf("train2")).isEqualTo(RELEASED_AT - REFUSED_AT)
		assertThat(blockedSecondsOf("train1")).isEqualTo(0.0)
		assertThat(service.blockedSeconds(now = END_AT)).isEqualTo(mapOf("train2" to RELEASED_AT - REFUSED_AT))
		assertThat(metrics.runEndSummary().unresolvedAtRunEnd).isEqualTo(0)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `a blocked train that holds no block stays blocked to the run end after its release`() {
		// The SP12.1 quirk, pinned (not changed): releasing train2, which holds nothing, keeps its contention.
		runScript { s -> s.releasePath("train2") }

		assertThat(blockedSecondsOf("train2")).isEqualTo(END_AT - REFUSED_AT)
		assertThat(metrics.runEndSummary().unresolvedAtRunEnd).isEqualTo(1)
		// The flush emptied the tracking; the metrics keep what was open just before the run ended.
		assertThat(service.openContentions()).isEmpty()
		assertThat(metrics.latestOpenContentions()).single().transform { it.trainId }.isEqualTo("train2")
	}

	private companion object {
		const val REFUSED_AT = 10.0
		const val OBSERVED_AT = 12.0
		const val RELEASED_AT = 25.0
		const val END_AT = 30.0
	}
}
