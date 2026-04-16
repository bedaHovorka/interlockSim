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
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
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
 * Covers both branches of line 282 of AbstractPath.kt:
 * - Null branch: `previousSwitch == null` -> ABSOLUTE_MAX_SPEED (existing test)
 * - Non-null branch: `previousSwitch?.allowedSpeed()` when a switch precedes a semaphore
 *
 * ## Approach
 *
 * 1. Load XML and create a SimulationContext
 * 2. Build a full path from topology results
 * 3. Configure switches in the path (same as PathReservationService would)
 * 4. Call path.setUpPath(startSep, trainId) which triggers setUpSemaphores()
 *
 * ## Topologies
 *
 * ### vyhybna.xml (null branch)
 * InOut_B(30,8) - track - zB(27,8) - track - vB(26,8) - track - doB1(25,8)
 *   ... middle tracks ...
 * doA1(16,8) - track - vA(15,8) - track - zA(14,8) - track - InOut_A(11,8)
 *
 * ### switch-between-semaphores.xml (non-null branch)
 * InOut_A(5,10) - track - semA(8,10) - track - sw1(12,10) - track - semB(15,10) - track - InOut_B(18,10)
 *                                                    \- siding - InOut_C(18,11)
 * Both semaphores have orientation=false (direction=F, facing toward higher X).
 * Backward iteration from InOut_B encounters previousTrack on the F-segment side,
 * so isSeparatorInDirection returns true for both semaphores:
 * semB (in-direction, prevSwitch=null -> null branch), sw1 (prevSwitch=sw1),
 * semA (in-direction, prevSwitch=sw1 -> non-null branch at line 282).
 *
 * @since Issue #357
 */
@DisplayName("AbstractPath.setUpSemaphores Coverage (Issue #357)")
@Tag("integration-test")
@Timeout(30, unit = SECONDS)
class AbstractPathSetUpSemaphoresTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
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

	@Test
	@DisplayName("setUpSemaphores uses switch allowedSpeed when preceding switch exists")
	fun setUpSemaphoresUsesSwitchSpeedWhenPrecedingSwitchExists() {
		val xmlStream = TestFixtures.loadSwitchBetweenSemaphoresXml()

		val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
		val simulationContext =
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

		try {
			// Step 1: Get topology navigator
			val navigator: TopologyNavigator = simulationContext.scope.get()

			// Step 2: Get InOut elements - find A and B by name
			val inOutsList = simulationContext.getInOuts().toList()
			val inOutA = inOutsList.first { it.name == "A" }
			val inOutB = inOutsList.first { it.name == "B" }

			// Step 3: Get topology path (track sections) for A -> B
			val candidatePaths = navigator.findAllTopologicalPaths(inOutA, inOutB)
			assertThat(candidatePaths.size).isGreaterThanOrEqualTo(1)
			val trackSections: List<TrackSection> = candidatePaths[0]

			// Step 4: Build an ArrayPath from topology results
			val path = ArrayPath(simulationContext)
			path.add(inOutA)
			var currentSeparator: DynamicPathSeparator = inOutA
			for (trackSection in trackSections) {
				path.add(trackSection)
				val staticResult = trackSection.getSecondEnd(currentSeparator)
				currentSeparator = simulationContext.toDynamic(staticResult)
				path.add(currentSeparator)
			}
			assertThat(path.size).isGreaterThanOrEqualTo(3)

			// Step 5: Configure switches in the path
			val pathElements = path.toList()
			val trainOccupant = TestTrackOccupant("test-train-switch")
			for ((index, element) in pathElements.withIndex()) {
				if (element is DynamicRailSwitch) {
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
			// setUpSemaphores(inOutA) iterates backward from getSecondEnd(inOutA) = inOutB.
			// Both semaphores have orientation=false -> direction()=F, matching the
			// F-segment where previousTrack connects during backward iteration.
			// Backward iteration: InOut_B, track, semB (direction=F, prevSwitch=null),
			//   track, sw1 (switch -> prevSwitch=sw1), track,
			//   semA (direction=F, prevSwitch=sw1 -> non-null branch at line 282)
			path.setUpPath(inOutA, "test-train-switch")

			// Step 7: Verify the path was successfully set up
			val isSetUp = path.isSetUpPath(inOutA)
			assertThat(isSetUp).isTrue()
		} finally {
			simulationContext.close()
		}
	}
}
