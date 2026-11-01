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
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.single
import cz.vutbr.fit.interlockSim.sim.TrainJourneyEvent
import kotlin.test.Test

/**
 * Unit tests for [JourneyRecorder]: one [TrainJourneyRecord] per journey, built from the
 * [TrainJourneyEvent] stream.
 *
 * @since Issue #673 (Goal 6 SP2)
 */
class JourneyRecorderTest {
	private val recorder = JourneyRecorder()

	@Test
	fun `a new recorder has no records`() {
		assertThat(recorder.records()).isEmpty()
	}

	@Test
	fun `a full journey fills every field`() {
		recorder.record(TrainJourneyEvent.Queued("T1", entryInOut = "A", plannedExit = "B", time = 1.0))
		recorder.record(TrainJourneyEvent.Admitted("T1", time = 4.0))
		recorder.record(TrainJourneyEvent.Exited("T1", actualExitInOut = "B", time = 50.0))

		assertThat(recorder.records()).containsExactly(
			TrainJourneyRecord(
				trainId = "T1",
				entryInOut = "A",
				entryTime = 1.0,
				admissionTime = 4.0,
				plannedExit = "B",
				actualExit = "B",
				exitTime = 50.0
			)
		)
		assertThat(recorder.records().single().wrongExit).isFalse()
	}

	@Test
	fun `the counter slots start at zero for the later lanes to fill`() {
		recorder.record(TrainJourneyEvent.Queued("T1", "A", "B", 1.0))

		val record = recorder.records().single()
		assertThat(record.blockedSeconds).isEqualTo(0.0)
		assertThat(record.expiries).isEqualTo(0)
	}

	@Test
	fun `an exit at another InOut than planned is a wrong exit`() {
		recorder.record(TrainJourneyEvent.Queued("T1", "A", "B", 1.0))
		recorder.record(TrainJourneyEvent.Admitted("T1", 2.0))
		recorder.record(TrainJourneyEvent.Exited("T1", "A", 30.0))

		assertThat(recorder.records().single().wrongExit).isTrue()
	}

	@Test
	fun `a journey without a planned exit is never a wrong exit`() {
		recorder.record(TrainJourneyEvent.Admitted("T1", 2.0))
		recorder.record(TrainJourneyEvent.Exited("T1", "A", 30.0))

		assertThat(recorder.records().single().wrongExit).isFalse()
	}

	@Test
	fun `a journey that has not exited yet is not a wrong exit`() {
		recorder.record(TrainJourneyEvent.Queued("T1", "A", "B", 1.0))

		assertThat(recorder.records().single().wrongExit).isFalse()
	}

	@Test
	fun `an admission without a prior queue event opens a record with unknown entry`() {
		recorder.record(TrainJourneyEvent.Admitted("T1", 3.0))

		val record = recorder.records().single()
		assertThat(record.trainId).isEqualTo("T1")
		assertThat(record.admissionTime).isEqualTo(3.0)
		assertThat(record.entryInOut).isNull()
		assertThat(record.entryTime).isNull()
		assertThat(record.plannedExit).isNull()
	}

	@Test
	fun `an exit without an open journey still yields a record`() {
		recorder.record(TrainJourneyEvent.Exited("T1", "B", 9.0))

		val record = recorder.records().single()
		assertThat(record.actualExit).isEqualTo("B")
		assertThat(record.exitTime).isEqualTo(9.0)
		assertThat(record.admissionTime).isNull()
	}

	@Test
	fun `the same train journeying again gets a new record`() {
		recorder.record(TrainJourneyEvent.Queued("T1", "A", "B", 1.0))
		recorder.record(TrainJourneyEvent.Admitted("T1", 2.0))
		recorder.record(TrainJourneyEvent.Exited("T1", "B", 20.0))
		recorder.record(TrainJourneyEvent.Queued("T1", "B", "A", 30.0))
		recorder.record(TrainJourneyEvent.Admitted("T1", 31.0))

		val records = recorder.records()
		assertThat(records.map { it.entryTime }).containsExactly(1.0, 30.0)
		assertThat(records[1].exitTime).isNull()
		assertThat(records[1].admissionTime).isEqualTo(31.0)
	}

	@Test
	fun `a second admission of an admitted train starts a new record`() {
		recorder.record(TrainJourneyEvent.Admitted("T1", 2.0))
		recorder.record(TrainJourneyEvent.Admitted("T1", 5.0))

		assertThat(recorder.records().map { it.admissionTime }).containsExactly(2.0, 5.0)
	}

	@Test
	fun `a second queue event of an open journey starts a new record`() {
		recorder.record(TrainJourneyEvent.Queued("T1", "A", "B", 1.0))
		recorder.record(TrainJourneyEvent.Queued("T1", "A", "B", 3.0))

		assertThat(recorder.records().map { it.entryTime }).containsExactly(1.0, 3.0)
	}

	@Test
	fun `records of interleaved trains stay apart and keep the order they were opened in`() {
		recorder.record(TrainJourneyEvent.Queued("T1", "A", "B", 1.0))
		recorder.record(TrainJourneyEvent.Queued("T2", "B", "A", 2.0))
		recorder.record(TrainJourneyEvent.Admitted("T2", 3.0))
		recorder.record(TrainJourneyEvent.Admitted("T1", 4.0))
		recorder.record(TrainJourneyEvent.Exited("T2", "A", 10.0))

		val records = recorder.records()
		assertThat(records.map { it.trainId }).containsExactly("T1", "T2")
		assertThat(records[0].admissionTime).isEqualTo(4.0)
		assertThat(records[0].exitTime).isNull()
		assertThat(records[1].exitTime).isEqualTo(10.0)
	}

	@Test
	fun `records returns a copy that later events do not change`() {
		recorder.record(TrainJourneyEvent.Queued("T1", "A", "B", 1.0))
		val before = recorder.records()

		recorder.record(TrainJourneyEvent.Admitted("T1", 2.0))

		assertThat(before.single().admissionTime).isNull()
	}
}
