package cz.vutbr.fit.interlockSim.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [Train.formatApprovalMessage] — the structured TRAIN_APPROVED payload format.
 *
 * These tests verify the contract between [Train] (producer) and [TextReporter] (consumer)
 * without requiring a full simulation environment.
 */
class TrainApprovalFormatTest {
	@Test
	fun formatApprovalMessageBasicFormat() {
		val msg = Train.formatApprovalMessage("Train #1", "IO1", "IO2")
		assertEquals("""train="Train #1" route=IO1->IO2""", msg)
	}

	@Test
	fun formatApprovalMessagePreservesSpacesInName() {
		val msg = Train.formatApprovalMessage("Express Train #42", "StationA", "StationB")
		assertTrue(msg.contains("""train="Express Train #42""""), "Name with spaces preserved: $msg")
	}

	@Test
	fun formatApprovalMessageWithSingleCharNames() {
		val msg = Train.formatApprovalMessage("T", "A", "B")
		assertEquals("""train="T" route=A->B""", msg)
	}

	@Test
	fun formatApprovalMessageMatchesTextReporterRegex() {
		// Verify the regex used by TextReporter can parse this format
		val regex = Regex("""train="([^"]+)"""")
		val msg = Train.formatApprovalMessage("IC 503", "Praha", "Brno")
		val match = regex.find(msg)
		assertTrue(match != null, "TextReporter regex should match: $msg")
		assertEquals("IC 503", match!!.groupValues[1])
	}

	@Test
	fun formatApprovalMessageRouteArrow() {
		val msg = Train.formatApprovalMessage("T1", "InA", "OutB")
		assertTrue(msg.contains("route=InA->OutB"), "Route uses arrow notation: $msg")
	}
}
