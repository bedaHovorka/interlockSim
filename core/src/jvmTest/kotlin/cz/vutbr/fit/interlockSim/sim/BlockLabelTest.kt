/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Unit tests for [blockLabel], which derives the human-readable block name used in
 * "enter block <name>" / "leave block <name>" reports.
 */
class BlockLabelTest {
	private fun section(
		name: String?,
		ends: Array<PathSeparator>
	): TrackSection {
		val block = mockk<TrackBlock>(relaxed = true)
		every { block.name } returns name
		every { block.ends() } returns ends
		val section = mockk<TrackSection>(relaxed = true)
		every { section.getTrackBlock() } returns block
		return section
	}

	private fun semaphore(name: String): RailSemaphore = RailSemaphore(name, true, Cell.SpatialType.HORIZONTAL)

	@Test
	fun `explicit block name is used verbatim`() {
		val section = section("B1", arrayOf(semaphore("kB"), semaphore("k1")))
		assertThat(blockLabel(section)).isEqualTo("B1")
	}

	@Test
	fun `unnamed block derives a stable label from its end separators`() {
		val section = section(null, arrayOf(semaphore("kB"), semaphore("k1")))
		// Sorted for stability so the same block yields the same label in both directions.
		assertThat(blockLabel(section)).isEqualTo("k1-kB")
	}

	@Test
	fun `label is direction independent`() {
		val forward = section(null, arrayOf(semaphore("kB"), semaphore("k1")))
		val reverse = section(null, arrayOf(semaphore("k1"), semaphore("kB")))
		assertThat(blockLabel(forward)).isEqualTo(blockLabel(reverse))
	}

	@Test
	fun `blank block name falls back to end separator label`() {
		val section = section("  ", arrayOf(semaphore("kA"), semaphore("kB")))
		assertThat(blockLabel(section)).isEqualTo("kA-kB")
	}

	@Test
	fun `label is unknown when no name is available`() {
		val section = section(null, arrayOf(semaphore(""), semaphore("")))
		assertThat(blockLabel(section)).isEqualTo("unknown")
	}
}
