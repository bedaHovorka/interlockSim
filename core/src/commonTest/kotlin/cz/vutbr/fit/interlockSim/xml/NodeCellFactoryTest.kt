package cz.vutbr.fit.interlockSim.xml

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import kotlin.test.Test

class NodeCellFactoryTest {
	@Test
	fun tagNameFor_returns_correct_tag_for_InOut() {
		val inOut = InOut("A", false, Cell.SpatialType.HORIZONTAL)
		assertThat(NodeCellFactory.tagNameFor(inOut)).isEqualTo("InOut")
	}

	@Test
	fun tagNameFor_returns_correct_tag_for_RailSwitch() {
		val sw = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_LEFT_FALSE)
		assertThat(NodeCellFactory.tagNameFor(sw)).isEqualTo("RailSwitch")
	}

	@Test
	fun tagNameFor_returns_correct_tag_for_RailSemaphore() {
		val sem = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
		assertThat(NodeCellFactory.tagNameFor(sem)).isEqualTo("RailSemaphore")
	}

	@Test
	fun isKnownTag_returns_true_for_known_tags() {
		assertThat(NodeCellFactory.isKnownTag("InOut")).isTrue()
		assertThat(NodeCellFactory.isKnownTag("RailSwitch")).isTrue()
		assertThat(NodeCellFactory.isKnownTag("RailSemaphore")).isTrue()
		assertThat(NodeCellFactory.isKnownTag("SimpleTrackBlock")).isTrue()
		assertThat(NodeCellFactory.isKnownTag("net")).isTrue()
	}

	@Test
	fun isKnownTag_returns_false_for_unknown_tags() {
		assertThat(NodeCellFactory.isKnownTag("Unknown")).isFalse()
		assertThat(NodeCellFactory.isKnownTag("")).isFalse()
	}
}
