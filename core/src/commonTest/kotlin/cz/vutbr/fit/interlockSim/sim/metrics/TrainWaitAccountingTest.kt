/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.single
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.context.navigation.ContentionQuery
import cz.vutbr.fit.interlockSim.context.navigation.OpenContention
import cz.vutbr.fit.interlockSim.context.navigation.ReservationExpired
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.FailedReservationKind
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.sim.RunAccountingEvent
import cz.vutbr.fit.interlockSim.sim.RunEnded
import cz.vutbr.fit.interlockSim.sim.TrainJourneyEvent
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Unit tests for [TrainWaitAccounting] (Issue #1305, Goal 6 SP7), driven with synthetic events and
 * a scripted [ContentionQuery], no simulation run.
 */
class TrainWaitAccountingTest {
	/** A [ContentionQuery] whose answers the test sets; it records the `now` it was asked for. */
	private class ScriptedContentions : ContentionQuery {
		var open: List<OpenContention> = emptyList()
		var blocked: Map<String, Double> = emptyMap()
		var askedNow: Double? = null

		override fun openContentions(): List<OpenContention> = open

		override fun blockedSeconds(now: Double): Map<String, Double> {
			askedNow = now
			return blocked
		}
	}

	private val contentions = ScriptedContentions()
	private val accounting = TrainWaitAccounting { contentions }
	private var context: DefaultSimulationContext? = null

	@BeforeTest
	fun setUpKoin() {
		startKoin { modules(commonCoreTestModule) }
	}

	@AfterTest
	fun tearDown() {
		context?.close()
		context = null
		stopKoin()
	}

	private fun realBlock(): DynamicTrackBlock {
		val ctx =
			CommonTestFixtures.parseSimulationContext(
				NetworkResources.LINEAR_TRACK_XML,
				DefaultSimulationProcessFactory()
			)
		context = ctx
		return ctx.getGraph().values().first()
	}

	private fun queue(
		trainId: String,
		time: Double
	) = accounting.onJourney(TrainJourneyEvent.Queued(trainId, "A", "B", time))

	private fun admit(
		trainId: String,
		time: Double
	) = accounting.onJourney(TrainJourneyEvent.Admitted(trainId, time))

	private fun refuse(
		trainId: String,
		kind: FailedReservationKind,
		time: Double
	) = accounting.onAccounting(RunAccountingEvent.ReservationRefused(trainId, kind, time))

	private fun grant(
		trainId: String,
		time: Double
	) = accounting.onAccounting(RunAccountingEvent.RouteGranted(trainId, time))

	private fun expire(
		trainId: String,
		kind: ReservationExpired.Kind,
		time: Double
	) = accounting.onExpired(ReservationExpired(trainId, kind, emptyList(), emptyList(), 60.0, 60.0, time))

	private fun recordOf(
		trainId: String,
		entryTime: Double? = null,
		admissionTime: Double? = null
	) = TrainJourneyRecord(trainId = trainId, entryTime = entryTime, admissionTime = admissionTime)

	// ── Queue time ──────────────────────────────────────────────────────────

	@Test
	fun `queue time runs from the queue step to the admission`() {
		val record = accounting.decorate(listOf(recordOf("T1", entryTime = 2.0, admissionTime = 9.5))).single()

		assertThat(record.queueSeconds).isEqualTo(7.5)
	}

	@Test
	fun `a train never admitted is queued up to the run end`() {
		queue("T1", 4.0)
		accounting.onRunEnded(RunEnded(time = 30.0, endTimeReached = true))

		assertThat(accounting.decorate(listOf(recordOf("T1", entryTime = 4.0))).single().queueSeconds)
			.isEqualTo(26.0)
	}

	@Test
	fun `a train the loop gave up is queued up to its entry failure`() {
		queue("T1", 4.0)
		accounting.onAccounting(
			RunAccountingEvent.EntryFailed(
				MultiTrainLoop.EntryFailure("T1", "A", "B", MultiTrainLoop.EntryFailureKind.NO_ROUTE, 1, null, 6.0)
			)
		)
		accounting.onRunEnded(RunEnded(time = 30.0, endTimeReached = true))

		assertThat(accounting.decorate(listOf(recordOf("T1", entryTime = 4.0))).single().queueSeconds)
			.isEqualTo(2.0)
		assertThat(accounting.pressure().queuedTrains).isEqualTo(0)
	}

	@Test
	fun `a journey with no queue step has no queue time`() {
		assertThat(accounting.decorate(listOf(recordOf("T1", admissionTime = 1.0))).single().queueSeconds).isNull()
	}

	// ── Standing at a signal with no route ──────────────────────────────────

	@Test
	fun `standing with no route runs from the first refusal of an admitted train to its next grant`() {
		admit("T1", 1.0)
		refuse("T1", FailedReservationKind.ALL_PATHS_BLOCKED, 10.0)
		refuse("T1", FailedReservationKind.NO_ROUTE, 12.0)
		grant("T1", 15.0)
		refuse("T1", FailedReservationKind.NO_ROUTE, 20.0)
		grant("T1", 21.0)

		assertThat(accounting.decorate(listOf(recordOf("T1"))).single().noRouteStandingSeconds).isEqualTo(6.0)
	}

	@Test
	fun `refusals before admission count as queue time and not as standing`() {
		queue("T1", 0.0)
		refuse("T1", FailedReservationKind.ALL_PATHS_BLOCKED, 1.0)
		grant("T1", 5.0)
		admit("T1", 5.0)

		assertThat(accounting.decorate(listOf(recordOf("T1"))).single().noRouteStandingSeconds).isEqualTo(0.0)
	}

	@Test
	fun `other refusal kinds do not open a standing interval`() {
		admit("T1", 0.0)
		refuse("T1", FailedReservationKind.CONFLICT, 1.0)
		refuse("T1", FailedReservationKind.DIVERGES_FROM_HELD_ROUTE, 2.0)
		grant("T1", 5.0)

		assertThat(accounting.decorate(listOf(recordOf("T1"))).single().noRouteStandingSeconds).isEqualTo(0.0)
	}

	@Test
	fun `an open standing interval closes at the exit or the run end`() {
		admit("T1", 0.0)
		admit("T2", 0.0)
		refuse("T1", FailedReservationKind.NO_ROUTE, 2.0)
		refuse("T2", FailedReservationKind.NO_ROUTE, 3.0)
		accounting.onJourney(TrainJourneyEvent.Exited("T1", "B", 7.0))
		accounting.onRunEnded(RunEnded(time = 10.0, endTimeReached = true))

		val records = accounting.decorate(listOf(recordOf("T1"), recordOf("T2")))

		assertThat(records.map { it.noRouteStandingSeconds }).containsExactly(5.0, 7.0)
	}

	// ── Expiries and blocked time ───────────────────────────────────────────

	@Test
	fun `expiries are counted per train and kind`() {
		expire("T1", ReservationExpired.Kind.WHOLE_HOLDING, 60.0)
		expire("T1", ReservationExpired.Kind.OCCUPYING_TRAIN_TAIL, 70.0)
		expire("T1", ReservationExpired.Kind.OCCUPYING_TRAIN_TAIL, 80.0)
		expire("T2", ReservationExpired.Kind.WHOLE_HOLDING, 90.0)

		val (t1, t2) = accounting.decorate(listOf(recordOf("T1"), recordOf("T2")))

		assertThat(listOf(t1.expiredHoldingCount, t1.expiredTailCount, t1.expiries)).containsExactly(1, 2, 3)
		assertThat(listOf(t2.expiredHoldingCount, t2.expiredTailCount, t2.expiries)).containsExactly(1, 0, 1)
	}

	@Test
	fun `blocked time comes from the contention query measured up to the run end`() {
		contentions.blocked = mapOf("T1" to 12.5)
		accounting.onRunEnded(RunEnded(time = 40.0, endTimeReached = false))

		val (t1, t2) = accounting.decorate(listOf(recordOf("T1"), recordOf("T2")))

		assertThat(t1.blockedSeconds).isEqualTo(12.5)
		assertThat(t2.blockedSeconds).isEqualTo(0.0)
		assertThat(contentions.askedNow).isEqualTo(40.0)
	}

	@Test
	fun `before the run end open intervals are measured up to the latest event seen`() {
		admit("T1", 0.0)
		refuse("T1", FailedReservationKind.NO_ROUTE, 2.0)
		grant("T2", 9.0)

		val record = accounting.decorate(listOf(recordOf("T1"))).single()

		assertThat(record.noRouteStandingSeconds).isEqualTo(7.0)
		assertThat(contentions.askedNow).isEqualTo(9.0)
	}

	@Test
	fun `a conflict event moves the measuring time of open intervals forward`() {
		admit("T1", 0.0)
		refuse("T1", FailedReservationKind.NO_ROUTE, 2.0)
		accounting.onConflict(ConflictDetectedEvent(realBlock(), "T2", "T1", time = 9.0))

		val record = accounting.decorate(listOf(recordOf("T1"))).single()

		assertThat(record.noRouteStandingSeconds).isEqualTo(7.0)
		assertThat(contentions.askedNow).isEqualTo(9.0)
	}

	@Test
	fun `a train journeying twice gets its train figures on its latest journey only`() {
		expire("T1", ReservationExpired.Kind.WHOLE_HOLDING, 1.0)

		val records = accounting.decorate(listOf(recordOf("T1"), recordOf("T1")))

		assertThat(records.map { it.expiries }).containsExactly(0, 1)
	}

	@Test
	fun `with no contention query the blocked time reads zero`() {
		val bare = TrainWaitAccounting { null }

		assertThat(bare.decorate(listOf(recordOf("T1"))).single().blockedSeconds).isEqualTo(0.0)
		bare.onBlockEvent(BlockEvent.BlockReleased(realBlock(), "T1", 1.0))
		assertThat(bare.pressure()).isEqualTo(CapacityPressure(0, 0, 0))
	}

	// ── Capacity pressure and the published open contentions ────────────────

	@Test
	fun `a block event samples the queued trains and the open contentions`() {
		val block = realBlock()
		queue("T1", 0.0)
		queue("T2", 0.0)
		queue("T3", 0.0)
		admit("T1", 1.0)
		contentions.open =
			listOf(
				OpenContention("T2", block, "T1", 2.0),
				OpenContention("T3", block, "T1", 2.5),
				OpenContention("T3", block, "T1", 3.0)
			)

		accounting.onBlockEvent(BlockEvent.BlockReleased(block, "T1", 4.0))

		assertThat(accounting.pressure())
			.isEqualTo(CapacityPressure(queuedTrains = 2, blockedTrains = 2, openContentions = 3))
		assertThat(accounting.latestOpenContentions()).isEqualTo(contentions.open)
	}

	@Test
	fun `the end-of-run flush does not overwrite the last sample`() {
		val block = realBlock()
		contentions.open = listOf(OpenContention("T2", block, "T1", 2.0))
		accounting.onBlockEvent(BlockEvent.BlockReleased(block, "T1", 4.0))
		contentions.open = emptyList()

		accounting.onBlockEvent(BlockEvent.ReservationConflictDetected(block, "T2", "T1", 600.0, atRunEnd = true))

		assertThat(accounting.pressure().openContentions).isEqualTo(1)
		assertThat(accounting.latestOpenContentions()).single().isEqualTo(OpenContention("T2", block, "T1", 2.0))
	}

	@Test
	fun `a conflict or a route grant refreshes the published open contentions`() {
		val block = realBlock()
		contentions.open = listOf(OpenContention("T2", block, "T1", 2.0))
		accounting.refreshOpenContentions()
		assertThat(accounting.latestOpenContentions()).single().isEqualTo(OpenContention("T2", block, "T1", 2.0))

		contentions.open = emptyList()
		grant("T2", 5.0)

		assertThat(accounting.latestOpenContentions()).isEmpty()
	}
}
