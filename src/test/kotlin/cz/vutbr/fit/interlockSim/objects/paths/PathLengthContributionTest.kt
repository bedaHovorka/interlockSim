package cz.vutbr.fit.interlockSim.objects.paths

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PathElement Length Contribution Tests")
class PathLengthContributionTest : KoinTestBase() {

	private lateinit var mockContext: cz.vutbr.fit.interlockSim.testutil.MockSimulationContext

	@BeforeEach
	fun setUp() {
		mockContext = createMockSimulationContext()
	}

	@Test
	fun `PathSeparator contributes zero length`() {
		// Given: A PathSeparator (InOut, Semaphore, or Switch)
		val inOut = InOut("TestInOut", true, Cell.SpatialType.HORIZONTAL)

		// When: We ask for length contribution
		val contribution = inOut.contributeToPathLength()

		// Then: PathSeparators have no physical length
		assertThat(contribution).isEqualTo(0.0)
	}

	@Test
	fun `Track contributes actual length`() {
		// Given: Two InOut points and a track connecting them
		val inA = InOut("A", false, Cell.SpatialType.HORIZONTAL)
		val outB = InOut("B", true, Cell.SpatialType.HORIZONTAL)
		val trackLength = 1000.0
		val track = SimpleTrackBlock(inA, outB, trackLength, 80.0)

		// When: We ask for length contribution
		val contribution = track.contributeToPathLength()

		// Then: Track returns its physical length
		assertThat(contribution).isEqualTo(trackLength)
		assertThat(contribution).isEqualTo(track.length())
	}

	@Test
	fun `Path length equals sum of track contributions`() {
		// Given: A path with separators and tracks
		val inA = InOut("A", false, Cell.SpatialType.HORIZONTAL)
		val outB = InOut("B", true, Cell.SpatialType.HORIZONTAL)
		val trackLength = 1000.0
		val track = SimpleTrackBlock(inA, outB, trackLength, 80.0)

		// Create a path containing: InOut -> Track -> InOut
		val path = ArrayPath(mockContext)
		path.addLast(inA)
		path.addLast(track)
		path.addLast(outB)

		// When: We calculate path length via contribution method
		var contributedLength = 0.0
		for (element in path) {
			contributedLength += element.contributeToPathLength()
		}

		// Then: Contributed length matches path.length()
		// Only the track contributes (InOuts contribute 0.0)
		assertThat(contributedLength).isEqualTo(trackLength)
		assertThat(contributedLength).isEqualTo(path.length())
	}
}
