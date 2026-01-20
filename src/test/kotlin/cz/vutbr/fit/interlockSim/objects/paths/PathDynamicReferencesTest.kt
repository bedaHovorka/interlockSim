/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Phase 7: Test that paths in simulation context contain only dynamic references
 */
package cz.vutbr.fit.interlockSim.objects.paths

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.tracks.Track
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.File

/**
 * Tests that paths created in simulation context contain only dynamic references.
 *
 * Phase 7: Validates that pathToNextSemaphore returns paths with dynamic PathSeparators.
 * This ensures consistent use of dynamic types throughout simulation, eliminating the need
 * for on-the-fly conversion during path traversal.
 */
@DisplayName("Path Dynamic References (Phase 7)")
@Tag("integration-test")
class PathDynamicReferencesTest : KoinTestBase() {
	private val factory: XMLContextFactory by inject()

	companion object {
		private val VYHYBNA_XML = File("src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
	}

	@Test
	@DisplayName("pathToNextSemaphore returns path with dynamic separators")
	fun pathToNextSemaphore_returnsDynamicSeparators() {
		// Arrange - Load vyhybna.xml and initialize mappings
		val context = factory.createContext(VYHYBNA_XML) as DefaultSimulationContext

		// Initialize ALL dynamic mappings (InOuts, RailSemaphores, RailSwitches)
		// Required because pathToNextSemaphore needs all separators mapped
		context.initializeDynamicMapping()

		// Get dynamic InOuts to start path search
		val dynamicInOuts = context.getInOuts()
		assertThat(dynamicInOuts).isNotNull()

		// Get first InOut's entry point and initial track section
		val firstInOut = dynamicInOuts.first()
		val staticInOut = firstInOut.staticRef
		
		// Get initial track section from InOut
		val firstSection = context.getNextTrackSection(staticInOut, null)
		assertThat(firstSection).isNotNull()

		// Act - Find path to next semaphore
		val path = context.pathToNextSemaphore(staticInOut, firstSection!!)

		// Assert - Path should exist and contain dynamic references
		assertThat(path).isNotNull()
		
		// Verify all PathSeparators in path are dynamic
		var separatorCount = 0
		var trackCount = 0
		for (element in path!!) {
			when (element) {
				is PathSeparator -> {
					separatorCount++
					// Phase 7: All separators in simulation paths must be dynamic
					assertThat(element)
						.isInstanceOf(DynamicPathSeparator::class)
					
					// Log the dynamic type for verification
					println("Path separator ${separatorCount}: ${element.javaClass.simpleName} (dynamic)")
				}
				is Track -> {
					trackCount++
					// Tracks remain static in paths (wrapped on-demand)
					println("Track ${trackCount}: ${element.javaClass.simpleName}")
				}
			}
		}

		// Verify path contains separators
		assertThat(separatorCount > 0)
			.isTrue()
		println("Path contains $separatorCount dynamic separators and $trackCount tracks")
	}

	@Test
	@DisplayName("pathToNextSemaphore handles dynamic input separator")
	fun pathToNextSemaphore_handlesDynamicInput() {
		// Arrange - Load configuration
		val context = factory.createContext(VYHYBNA_XML) as DefaultSimulationContext
		context.initializeDynamicMapping()
		val dynamicInOuts = context.getInOuts()
		
		// Get dynamic InOut (already dynamic)
		val dynamicInOut = dynamicInOuts.first()
		val firstSection = context.getNextTrackSection(dynamicInOut, null)
		assertThat(firstSection).isNotNull()

		// Act - Call with dynamic separator (should be idempotent)
		val path = context.pathToNextSemaphore(dynamicInOut, firstSection!!)

		// Assert - Should still work and return dynamic path
		assertThat(path).isNotNull()
		
		// Verify first element is dynamic
		val firstElement = path!!.getFirst()
		assertThat(firstElement)
			.isInstanceOf(DynamicPathSeparator::class)
	}

	@Test
	@DisplayName("pathToNextSemaphore converts static input to dynamic")
	fun pathToNextSemaphore_convertsStaticInput() {
		// Arrange - Load configuration
		val context = factory.createContext(VYHYBNA_XML) as DefaultSimulationContext
		context.initializeDynamicMapping()
		val dynamicInOuts = context.getInOuts()
		
		// Get STATIC InOut reference (before conversion)
		val dynamicInOut = dynamicInOuts.first()
		val staticInOut = dynamicInOut.staticRef // Get the static reference
		val firstSection = context.getNextTrackSection(staticInOut, null)
		assertThat(firstSection).isNotNull()

		// Act - Call with STATIC separator (should convert to dynamic)
		val path = context.pathToNextSemaphore(staticInOut, firstSection!!)

		// Assert - Should convert input and return dynamic path
		assertThat(path).isNotNull()
		
		// Verify first element is dynamic (not static)
		val firstElement = path!!.getFirst()
		assertThat(firstElement)
			.isInstanceOf(DynamicPathSeparator::class)
		
		// Verify it's NOT the static reference
		assertThat(firstElement !is InOut)
			.isTrue()
		assertThat(firstElement !is RailSemaphore)
			.isTrue()
		assertThat(firstElement !is RailSwitch)
			.isTrue()
	}

	@Test
	@DisplayName("path elements iteration returns dynamic separators")
	fun pathIteration_returnsDynamicSeparators() {
		// Arrange
		val context = factory.createContext(VYHYBNA_XML) as DefaultSimulationContext
		context.initializeDynamicMapping()
		val dynamicInOuts = context.getInOuts()
		val firstInOut = dynamicInOuts.first()
		val staticInOut = firstInOut.staticRef
		val firstSection = context.getNextTrackSection(staticInOut, null)
		assertThat(firstSection).isNotNull()

		// Act - Create path
		val path = context.pathToNextSemaphore(staticInOut, firstSection!!)
		assertThat(path).isNotNull()

		// Assert - Iterate and verify all separators are dynamic
		val separators = path!!.filter { it is PathSeparator }
		for (separator in separators) {
			assertThat(separator)
				.isInstanceOf(DynamicPathSeparator::class)
		}
	}
}
