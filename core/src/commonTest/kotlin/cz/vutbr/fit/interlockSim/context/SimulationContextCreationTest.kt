/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import cz.vutbr.fit.interlockSim.util.Point
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.koin.core.component.KoinComponent
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Tests for SimulationContext creation, transformation, and Koin scope lifecycle.
 *
 * Verifies that editing contexts can be transformed into simulation contexts,
 * that Koin scopes are properly created and closed, and that contexts created
 * from various XML networks are structurally correct.
 *
 * Runs on both JVM and linuxX64 via commonTest.
 *
 * @since 2026-03-24 (Issue #410 — increase native test coverage)
 */
class SimulationContextCreationTest : KoinComponent {

	private val processFactory = DefaultSimulationProcessFactory()

	@BeforeTest
	fun setUpKoin() {
		startKoin { modules(commonCoreTestModule) }
	}

	@AfterTest
	fun tearDownKoin() {
		stopKoin()
	}

	// --- Context transformation from XML ---

	@Test
	fun simulationContextFromLinearTrackXml() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.LINEAR_TRACK_XML,
			processFactory
		).use { simCtx ->
			assertThat(simCtx).isNotNull()
			assertThat(simCtx.getInOuts()).hasSize(2)
			assertThat(simCtx.isFrozen()).isTrue()
		}
	}

	@Test
	fun simulationContextFromVyhybnaXml() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.VYHYBNA_XML,
			processFactory
		).use { simCtx ->
			assertThat(simCtx).isNotNull()
			assertThat(simCtx.getInOuts()).hasSize(2)
			assertThat(simCtx.isFrozen()).isTrue()
		}
	}

	@Test
	fun simulationContextFromSwitchBasicXml() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.SWITCH_BASIC_XML,
			processFactory
		).use { simCtx ->
			assertThat(simCtx).isNotNull()
			assertThat(simCtx.getInOuts()).hasSize(3)
			assertThat(simCtx.isFrozen()).isTrue()
		}
	}

	@Test
	fun simulationContextFromSemaphoreBasicXml() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.SEMAPHORE_BASIC_XML,
			processFactory
		).use { simCtx ->
			assertThat(simCtx).isNotNull()
			assertThat(simCtx.getInOuts()).hasSize(2)
			assertThat(simCtx.isFrozen()).isTrue()
		}
	}

	@Test
	fun simulationContextFromParallelTracksXml() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.TWO_TRACKS_PARALLEL_XML,
			processFactory
		).use { simCtx ->
			assertThat(simCtx).isNotNull()
			assertThat(simCtx.getInOuts()).hasSize(4)
		}
	}

	// --- Context transformation preserves properties ---

	@Test
	fun transformationPreservesGridDimensions() {
		CommonTestFixtures.parseEditingContext(NetworkResources.LINEAR_TRACK_XML).use { editingCtx ->
			val editingCols = editingCtx.getRailWayNetGrid().cols
			val editingRows = editingCtx.getRailWayNetGrid().rows

			DefaultSimulationContext.fromEditingContext(editingCtx, processFactory).use { simCtx ->
				assertThat(simCtx.getRailWayNetGrid().cols).isEqualTo(editingCols)
				assertThat(simCtx.getRailWayNetGrid().rows).isEqualTo(editingRows)
			}
		}
	}

	@Test
	fun transformationPreservesTrackBlockCount() {
		CommonTestFixtures.parseEditingContext(NetworkResources.SWITCH_BASIC_XML).use { editingCtx ->
			val editingEdges = editingCtx.getGraph().entrySet().size

			DefaultSimulationContext.fromEditingContext(editingCtx, processFactory).use { simCtx ->
				assertThat(simCtx.getGraph().entrySet().size).isEqualTo(editingEdges)
			}
		}
	}

	@Test
	fun simulationContextIsFrozenAfterCreation() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.LINEAR_TRACK_XML,
			processFactory
		).use { simCtx ->
			assertThat(simCtx.isFrozen()).isTrue()
		}
	}

	// --- Koin scope lifecycle ---

	@Test
	fun simulationContextHasScope() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.LINEAR_TRACK_XML,
			processFactory
		).use { simCtx ->
			assertThat(simCtx.scope).isNotNull()
		}
	}

	@Test
	fun editingContextHasScope() {
		CommonTestFixtures.parseEditingContext(NetworkResources.LINEAR_TRACK_XML).use { editingCtx ->
			assertThat(editingCtx.scope).isNotNull()
		}
	}

	@Test
	fun multipleSimulationContextsCanBeCreatedSequentially() {
		// First context
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.LINEAR_TRACK_XML,
			processFactory
		).use { simCtx1 ->
			assertThat(simCtx1).isNotNull()
		}

		// Second context (after first is closed)
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.SWITCH_BASIC_XML,
			processFactory
		).use { simCtx2 ->
			assertThat(simCtx2).isNotNull()
			assertThat(simCtx2.getInOuts()).hasSize(3)
		}
	}

	// --- Programmatic context creation ---

	@Test
	fun simulationContextFromProgrammaticEditingContext() {
		DefaultEditingContext(30, 30).use { editingCtx ->
			val inA = InOut("A", false, Cell.SpatialType.HORIZONTAL)
			val inB = InOut("B", true, Cell.SpatialType.HORIZONTAL)
			val track = SimpleTrackBlock(inA, inB, 200.0, 60.0)
			editingCtx.putCell(Point(1, 1), inA)
			editingCtx.putCell(Point(10, 10), inB)
			editingCtx.joinCells(Point(1, 1), Point(10, 10), track)

			DefaultSimulationContext.fromEditingContext(editingCtx, processFactory).use { simCtx ->
				assertThat(simCtx).isNotNull()
				assertThat(simCtx.getInOuts()).hasSize(2)
				assertThat(simCtx.isFrozen()).isTrue()
				assertThat(simCtx.getGraph().entrySet().size).isEqualTo(1)
			}
		}
	}

	@Test
	fun emptySimulationContextCanBeCreated() {
		CommonTestFixtures.createEmptySimulationContext(processFactory).use { simCtx ->
			assertThat(simCtx).isNotNull()
			assertThat(simCtx.isFrozen()).isTrue()
		}
	}
}
