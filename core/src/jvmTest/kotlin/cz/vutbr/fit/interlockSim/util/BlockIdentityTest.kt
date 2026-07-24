/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [BlockIdentity.stableBlockId] (SP2b.8, Issue #695).
 *
 * The helper was extracted from `DefaultNetworkPerceptionPort` so that the dispatcher-agent
 * topology serializer emits block IDs identical to those in per-turn perception readings.
 */
@DisplayName("BlockIdentity.stableBlockId() — shared block-ID derivation (SP2b.8, #695)")
class BlockIdentityTest {
	private fun namedEndpoint(name: String): NodeCell =
		mockk<NodeCell>(relaxed = true).also { every { it.getName() } returns name }

	private fun block(
		name: String? = null,
		ends: Array<PathSeparator> = emptyArray()
	): DynamicTrackBlock =
		mockk<DynamicTrackBlock>(relaxed = true).also {
			every { it.name } returns name
			every { it.ends() } returns ends
		}

	@Test
	@DisplayName("prefers the block's explicit name")
	fun explicitNameWins() {
		val result = BlockIdentity.stableBlockId(block(name = "k1", ends = arrayOf(namedEndpoint("a"), namedEndpoint("b"))))

		assertThat(result).isEqualTo("k1")
	}

	@Test
	@DisplayName("falls back to sorted endpoint separator names joined with '-'")
	fun endpointFallbackIsSorted() {
		val result = BlockIdentity.stableBlockId(block(ends = arrayOf(namedEndpoint("vB"), namedEndpoint("doA1"))))

		assertThat(result).isEqualTo("doA1-vB")
	}

	@Test
	@DisplayName("blank explicit name still falls back to endpoint names")
	fun blankNameFallsBack() {
		val result = BlockIdentity.stableBlockId(block(name = "  ", ends = arrayOf(namedEndpoint("zA"), namedEndpoint("vA"))))

		assertThat(result).isEqualTo("vA-zA")
	}

	@Test
	@DisplayName("returns 'unknown' when no name is available from any source")
	fun noNameYieldsUnknown() {
		val result = BlockIdentity.stableBlockId(block())

		assertThat(result).isEqualTo("unknown")
	}
}
