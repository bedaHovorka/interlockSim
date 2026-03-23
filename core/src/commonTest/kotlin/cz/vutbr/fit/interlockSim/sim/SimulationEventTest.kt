package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SimulationEventTest {

	@Test
	fun formatTimeZero() {
		val event = SimulationEvent(0.0, ReportType.TRAIN_EVENTS, "src", "msg")
		assertEquals("00:00:00.000", event.formatTime())
	}

	@Test
	fun formatTimeHoursMinutesSeconds() {
		val event = SimulationEvent(3661.5, ReportType.TRAIN_EVENTS, "src", "msg")
		assertEquals("01:01:01.500", event.formatTime())
	}

	@Test
	fun formatTimeSubSecond() {
		val event = SimulationEvent(45.677, ReportType.TRAIN_EVENTS, "src", "msg")
		assertEquals("00:00:45.677", event.formatTime())
	}

	@Test
	fun formatForDisplayIncludesAllParts() {
		val event = SimulationEvent(1.0, ReportType.TRAIN_EVENTS, "vlak1", "stopped")
		assertEquals("[00:00:01.000] [TRAIN_EVENTS] vlak1 stopped", event.formatForDisplay())
	}

	@Test
	fun fromContextChangeEventParsesTrainEvent() {
		val cce = ContextChangeEvent("TRAIN_EVENTS", null, "14.5 vlak1 approved IO1->IO2")
		val event = SimulationEvent.fromContextChangeEvent(cce)!!
		assertEquals(14.5, event.simulationTime)
		assertEquals(ReportType.TRAIN_EVENTS, event.eventType)
		assertEquals("vlak1", event.source)
		assertEquals("approved IO1->IO2", event.message)
	}

	@Test
	fun fromContextChangeEventParsesTrainApprovedEvent() {
		val cce = ContextChangeEvent("TRAIN_APPROVED", null, """14.5 Train #1 train="Train #1" route=IO1->IO2""")
		val event = SimulationEvent.fromContextChangeEvent(cce)!!
		assertEquals(14.5, event.simulationTime)
		assertEquals(ReportType.TRAIN_APPROVED, event.eventType)
		assertEquals("Train", event.source)
		assertEquals("""#1 train="Train #1" route=IO1->IO2""", event.message)
	}

	@Test
	fun fromContextChangeEventParsesNodeEvent() {
		val cce = ContextChangeEvent("NODE_EVENTS", null, "10.0 IO1 waiting to free aPath")
		val event = SimulationEvent.fromContextChangeEvent(cce)!!
		assertEquals("IO1", event.source)
		assertEquals("waiting to free aPath", event.message)
	}

	@Test
	fun fromContextChangeEventReturnsNullForNonReportType() {
		val cce = ContextChangeEvent("cellAdded", null, "some value")
		assertNull(SimulationEvent.fromContextChangeEvent(cce))
	}

	@Test
	fun fromContextChangeEventReturnsNullForNullNewValue() {
		val cce = ContextChangeEvent("TRAIN_EVENTS", null, null)
		assertNull(SimulationEvent.fromContextChangeEvent(cce))
	}

	@Test
	fun fromContextChangeEventHandlesTwoPartMessage() {
		val cce = ContextChangeEvent("TRAIN_EVENTS", null, "5.0 vlak1")
		val event = SimulationEvent.fromContextChangeEvent(cce)!!
		assertEquals(5.0, event.simulationTime)
		assertEquals("vlak1", event.source)
		assertEquals("vlak1", event.message)
	}

	@Test
	fun formatTimeExactMinuteBoundary() {
		val event = SimulationEvent(60.0, ReportType.TRAIN_EVENTS, "src", "msg")
		assertEquals("00:01:00.000", event.formatTime())
	}

	@Test
	fun formatTimeExactHourBoundary() {
		val event = SimulationEvent(3600.0, ReportType.TRAIN_EVENTS, "src", "msg")
		assertEquals("01:00:00.000", event.formatTime())
	}

	@Test
	fun formatTimeLargeValue() {
		val event = SimulationEvent(86400.0, ReportType.TRAIN_EVENTS, "src", "msg")
		assertEquals("24:00:00.000", event.formatTime())
	}

	@Test
	fun formatTimeFloatingPointEdgeCase() {
		val event = SimulationEvent(45.678, ReportType.TRAIN_EVENTS, "src", "msg")
		assertEquals("00:00:45.678", event.formatTime())
	}

	@Test
	fun formatTimeMillisDoesNotOverflow() {
		// roundToInt() of (0.9995 * 1000) = 999.5 would give 1000 without coerceIn
		val event = SimulationEvent(1.9995, ReportType.TRAIN_EVENTS, "src", "msg")
		val result = event.formatTime()
		assertFalse(result.contains(".1000"), "millis must not overflow to 1000: $result")
		assertEquals("00:00:01.999", result)
	}

	@Test
	fun formatForDisplayWithEmptySource() {
		val event = SimulationEvent(1.0, ReportType.TRAIN_EVENTS, "", "stopped")
		assertEquals("[00:00:01.000] [TRAIN_EVENTS] stopped", event.formatForDisplay())
	}

	@Test
	fun fromContextChangeEventWorksForAllReportTypes() {
		for (type in ReportType.entries.filter { it != ReportType._DEBUG }) {
			val cce = ContextChangeEvent(type.name, null, "1.0 src msg")
			val event = SimulationEvent.fromContextChangeEvent(cce)!!
			assertEquals(type, event.eventType)
		}
	}

	@Test
	fun dataClassEquality() {
		val a = SimulationEvent(1.0, ReportType.TRAIN_EVENTS, "src", "msg")
		val b = SimulationEvent(1.0, ReportType.TRAIN_EVENTS, "src", "msg")
		assertEquals(a, b)
	}
}
