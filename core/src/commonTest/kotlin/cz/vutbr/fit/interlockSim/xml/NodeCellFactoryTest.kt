package cz.vutbr.fit.interlockSim.xml

import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeCellFactoryTest {

	@Test
	fun tagNameFor_returns_correct_tag_for_InOut() {
		val inOut = InOut("A", false, Cell.SpatialType.HORIZONTAL)
		assertEquals("InOut", NodeCellFactory.tagNameFor(inOut))
	}

	@Test
	fun tagNameFor_returns_correct_tag_for_RailSwitch() {
		val sw = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_LEFT_FALSE)
		assertEquals("RailSwitch", NodeCellFactory.tagNameFor(sw))
	}

	@Test
	fun tagNameFor_returns_correct_tag_for_RailSemaphore() {
		val sem = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
		assertEquals("RailSemaphore", NodeCellFactory.tagNameFor(sem))
	}

	@Test
	fun isKnownTag_returns_true_for_known_tags() {
		assertTrue(NodeCellFactory.isKnownTag("InOut"))
		assertTrue(NodeCellFactory.isKnownTag("RailSwitch"))
		assertTrue(NodeCellFactory.isKnownTag("RailSemaphore"))
		assertTrue(NodeCellFactory.isKnownTag("SimpleTrackBlock"))
		assertTrue(NodeCellFactory.isKnownTag("net"))
	}

	@Test
	fun isKnownTag_returns_false_for_unknown_tags() {
		assertFalse(NodeCellFactory.isKnownTag("Unknown"))
		assertFalse(NodeCellFactory.isKnownTag(""))
	}
}
