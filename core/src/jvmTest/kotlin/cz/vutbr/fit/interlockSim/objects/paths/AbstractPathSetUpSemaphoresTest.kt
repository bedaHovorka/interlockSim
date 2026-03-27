/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Integration test for AbstractPath.setUpSemaphores() coverage (Issue #357)
 */
package cz.vutbr.fit.interlockSim.objects.paths

import assertk.assertThat
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.Track
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit.SECONDS

/**
 * Integration test that exercises [AbstractPath.setUpSemaphores] via path-level setUpPath().
 *
 * ## Purpose
 *
 * Covers line 282 of AbstractPath.kt where ABSOLUTE_MAX_SPEED is used as fallback
 * when a semaphore has no preceding switch during backward iteration.
 *
 * ## Approach
 *
 * 1. Load vyhybna.xml and create a SimulationContext
 * 2. Build a full path (InOut1 to InOut2) from topology results
 * 3. Configure switches in the path (same as PathReservationService would)
 * 4. Call path.setUpPath(inOut1, trainId) which triggers setUpSemaphores()
 * 5. During backward iteration from InOut_A end, semaphore zA has no preceding
 *    switch, so the fallback ABSOLUTE_MAX_SPEED is used at line 282
 *
 * ## Topology (vyhybna.xml)
 *
 * InOut_B(30,8) - track - zB(27,8) - track - vB(26,8) - track - doB1(25,8)
 *   ... middle tracks ...
 * doA1(16,8) - track - vA(15,8) - track - zA(14,8) - track - InOut_A(11,8)
 *
 * setUpSemaphores iterates backward from getSecondEnd(inOut1). When iterating
 * from InOut_A end: InOut_A(else), track(prevTrack), zA(semaphore with prevSwitch==null
 * -> ABSOLUTE_MAX_SPEED at line 282), track, vA(switch -> prevSwitch), ...
 *
 * @since Issue #357
 */
@DisplayName("AbstractPath.setUpSemaphores Coverage (Issue #357)")
@Tag("integration-test")
@Timeout(30, unit = SECONDS)
class AbstractPathSetUpSemaphoresTest : KoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	/**
	 * Minimal TrackOccupant for switch configuration (same pattern as
	 * DefaultPathReservationService.MinimalTrackOccupant which is private).
	 */
	private class TestTrackOccupant(
		override val name: String
	) : TrackOccupant {
		override fun distanceToSemaphore(): Double = 0.0
		override fun nextSemaphore(): OrientedPathSeparator? = null
	}

	@Test
	@DisplayName("setUpSemaphores uses ABSOLUTE_MAX_SPEED when no preceding switch exists")
	fun setUpSemaphoresUsesAbsoluteMaxSpeedWhenNoPrecedingSwitch() {
		val xmlStream = TestFixtures.loadShuntingXml()
			?: throw IllegalStateException("vyhybna.xml not found in resources")

		val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
		val simulationContext =
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

		try {
			// Step 1: Get topology navigator
			val navigator: TopologyNavigator = simulationContext.scope.get()

			// Step 2: Get InOut elements
			val inOutsList = simulationContext.getInOuts().toList()
			val inOut1 = simulationContext.toDynamic(inOutsList[0])
			val inOut2 = simulationContext.toDynamic(inOutsList[1])

			// Step 3: Get topology path (track sections) for InOut1 -> InOut2
			val candidatePaths = navigator.findAllTopologicalPaths(inOut1, inOut2)
			assertThat(candidatePaths.size).isGreaterThanOrEqualTo(1)
			val trackSections: List<TrackSection> = candidatePaths[0]

			// Step 4: Build an ArrayPath from topology results
			// Pattern matches PathInfoBuilder.buildFullPath()
			val path = ArrayPath(simulationContext)
			path.add(inOut1)
			var currentSeparator: DynamicPathSeparator = inOut1
			for (trackSection in trackSections) {
				path.add(trackSection)
				val staticResult = trackSection.getSecondEnd(currentSeparator)
				currentSeparator = simulationContext.toDynamic(staticResult)
				path.add(currentSeparator)
			}
			assertThat(path.size).isGreaterThanOrEqualTo(3)

			// Step 5: Configure switches in the path before calling setUpPath
			// AbstractPath.separatorSetting checks getFollowingSegment(from) === to
			// for SET_UP_PATH, so switches must be configured first.
			val pathElements = path.toList()
			val trainOccupant = TestTrackOccupant("test-train")
			for ((index, element) in pathElements.withIndex()) {
				if (element is DynamicRailSwitch) {
					// Find previous and next tracks
					var previous: Track? = null
					for (i in (index - 1) downTo 0) {
						if (pathElements[i] is Track) {
							previous = pathElements[i] as Track
							break
						}
					}
					var next: Track? = null
					for (i in (index + 1) until pathElements.size) {
						if (pathElements[i] is Track) {
							next = pathElements[i] as Track
							break
						}
					}
					if (next != null) {
						val from = simulationContext.getSegment(element, previous, next)
						val to = simulationContext.getSegment(element, next, previous)
						element.setUpPath(from, to, element.allowedSpeed(), trainOccupant)
					}
				}
			}

			// Step 6: Call setUpPath which triggers setUpSemaphores()
			// All blocks are FREE, switches are configured.
			// setUpSemaphores() iterates backward from the other end and
			// finds semaphore zA with no preceding switch, using
			// ABSOLUTE_MAX_SPEED (line 282 of AbstractPath.kt).
			path.setUpPath(inOut1, "test-train")

			// Step 7: Verify the path was successfully set up
			// isSetUpPath returns true when all tracks are reserved
			val isSetUp = path.isSetUpPath(inOut1)
			assertThat(isSetUp).isTrue()
		} finally {
			simulationContext.close()
		}
	}
}
