/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SimulationEvent] data class.
 *
 * Tests event creation, time formatting, and display formatting.
 */
class SimulationEventTest {

	@Test
	fun `formatTime formats zero time correctly`() {
		val event = SimulationEvent(
			simulationTime = 0.0,
			eventType = ReportType.TRAIN_EVENTS,
			message = "Test event"
		)

		assertThat(event.formatTime()).isEqualTo("00:00:00.000")
	}

	@Test
	fun `formatTime formats seconds only`() {
		val event = SimulationEvent(
			simulationTime = 45.678,
			eventType = ReportType.TRAIN_EVENTS,
			message = "Test event"
		)

		assertThat(event.formatTime()).isEqualTo("00:00:45.677")
	}

	@Test
	fun `formatTime formats minutes and seconds`() {
		val event = SimulationEvent(
			simulationTime = 123.456,
			eventType = ReportType.TRAIN_EVENTS,
			message = "Test event"
		)

		assertThat(event.formatTime()).isEqualTo("00:02:03.456")
	}

	@Test
	fun `formatTime formats hours, minutes, and seconds`() {
		val event = SimulationEvent(
			simulationTime = 3661.5,
			eventType = ReportType.TRAIN_EVENTS,
			message = "Test event"
		)

		assertThat(event.formatTime()).isEqualTo("01:01:01.500")
	}

	@Test
	fun `formatTime handles fractional milliseconds`() {
		val event = SimulationEvent(
			simulationTime = 1.2345,
			eventType = ReportType.TRAIN_EVENTS,
			message = "Test event"
		)

		// Should truncate to 3 decimal places (234ms)
		assertThat(event.formatTime()).isEqualTo("00:00:01.234")
	}

	@Test
	fun `formatForDisplay includes time, type, and message`() {
		val event = SimulationEvent(
			simulationTime = 123.456,
			eventType = ReportType.TRAIN_EVENTS,
			message = "Train 1 stopped at signal S1"
		)

		val expected = "[00:02:03.456] [TRAIN_EVENTS] Train 1 stopped at signal S1"
		assertThat(event.formatForDisplay()).isEqualTo(expected)
	}

	@Test
	fun `formatForDisplay works with all report types`() {
		val types = listOf(
			ReportType.PATH_SETTING,
			ReportType.NODE_EVENTS,
			ReportType.TRAIN_EVENTS,
			ReportType.TRAIN_CONTINUOUS
		)

		types.forEach { type ->
			val event = SimulationEvent(
				simulationTime = 1.0,
				eventType = type,
				message = "Test"
			)

			val formatted = event.formatForDisplay()
			assertThat(formatted).isEqualTo("[00:00:01.000] [${type.name}] Test")
		}
	}

	@Test
	fun `trainContinuous factory creates event with correct format`() {
		val event = SimulationEvent.trainContinuous(
			simulationTime = 123.456,
			trainId = "Train1",
			position = 100.5,
			velocity = 25.3
		)

		assertThat(event.simulationTime).isEqualTo(123.456)
		assertThat(event.eventType).isEqualTo(ReportType.TRAIN_CONTINUOUS)
		assertThat(event.message).isEqualTo("Train Train1: pos=100.50m, vel=25.30m/s")
	}

	@Test
	fun `trainEvent factory creates event with correct format`() {
		val event = SimulationEvent.trainEvent(
			simulationTime = 45.6,
			trainId = "Train2",
			event = "stopped at signal S1"
		)

		assertThat(event.simulationTime).isEqualTo(45.6)
		assertThat(event.eventType).isEqualTo(ReportType.TRAIN_EVENTS)
		assertThat(event.message).isEqualTo("Train Train2: stopped at signal S1")
	}

	@Test
	fun `nodeEvent factory creates event with correct format`() {
		val event = SimulationEvent.nodeEvent(
			simulationTime = 30.0,
			nodeId = "Switch1",
			event = "switched to position A"
		)

		assertThat(event.simulationTime).isEqualTo(30.0)
		assertThat(event.eventType).isEqualTo(ReportType.NODE_EVENTS)
		assertThat(event.message).isEqualTo("Node Switch1: switched to position A")
	}

	@Test
	fun `pathSetting factory creates event with correct format`() {
		val event = SimulationEvent.pathSetting(
			simulationTime = 15.5,
			pathId = "Path1",
			action = "reserved"
		)

		assertThat(event.simulationTime).isEqualTo(15.5)
		assertThat(event.eventType).isEqualTo(ReportType.PATH_SETTING)
		assertThat(event.message).isEqualTo("Path Path1: reserved")
	}

	@Test
	fun `data class equality works correctly`() {
		val event1 = SimulationEvent(
			simulationTime = 1.0,
			eventType = ReportType.TRAIN_EVENTS,
			message = "Test"
		)
		val event2 = SimulationEvent(
			simulationTime = 1.0,
			eventType = ReportType.TRAIN_EVENTS,
			message = "Test"
		)
		val event3 = SimulationEvent(
			simulationTime = 2.0,
			eventType = ReportType.TRAIN_EVENTS,
			message = "Test"
		)

		assertThat(event1).isEqualTo(event2)
		assertThat(event1 == event3).isEqualTo(false)
	}

	@Test
	fun `formatTime handles very long simulation times`() {
		val event = SimulationEvent(
			simulationTime = 86400.0, // 24 hours
			eventType = ReportType.TRAIN_EVENTS,
			message = "Test"
		)

		assertThat(event.formatTime()).isEqualTo("24:00:00.000")
	}

	@Test
	fun `formatTime handles edge case of exactly one minute`() {
		val event = SimulationEvent(
			simulationTime = 60.0,
			eventType = ReportType.TRAIN_EVENTS,
			message = "Test"
		)

		assertThat(event.formatTime()).isEqualTo("00:01:00.000")
	}

	@Test
	fun `formatTime handles edge case of exactly one hour`() {
		val event = SimulationEvent(
			simulationTime = 3600.0,
			eventType = ReportType.TRAIN_EVENTS,
			message = "Test"
		)

		assertThat(event.formatTime()).isEqualTo("01:00:00.000")
	}
}
