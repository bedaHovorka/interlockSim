/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for ConflictResolutionListModel and ConflictResolutionPanel (Issue #590, Goal 9 SP5)
*/

package cz.vutbr.fit.interlockSim.gui.conflict

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.isEqualTo
import assertk.assertions.isEmpty
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolution
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Tests for [ConflictResolutionListModel] and [ConflictResolutionPanel].
 *
 * Covers EDT-safe mutations, callback wiring, and panel visibility lifecycle.
 *
 * @since Issue #590 (Goal 9 SP5)
 */
@DisplayName("ConflictResolutionListModel and ConflictResolutionPanel")
class ConflictResolutionPanelTest {
	private lateinit var model: ConflictResolutionListModel
	private lateinit var panel: ConflictResolutionPanel

	private val holdResolution =
		ConflictResolution.HoldTrain(
			trainId = "Train-A",
			holdDurationSeconds = 30.0,
			affectedTrains = listOf("Train-A"),
			estimatedImpact = ConflictResolution.EstimatedImpact(30.0, "Hold Train-A for 30s")
		)

	private val speedResolution =
		ConflictResolution.SpeedAdjust(
			trainId = "Train-A",
			speedReductionFactor = 0.5,
			affectedTrains = listOf("Train-A"),
			estimatedImpact = ConflictResolution.EstimatedImpact(0.0, "Reduce speed to 50%")
		)

	private val mockBlock = mockk<cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock>(relaxed = true) {
		every { name } returns "Block-7"
	}

	private val conflict =
		ConflictDetectedEvent(
			block = mockBlock,
			trainId = "Train-A",
			conflictingTrainId = "Train-B",
			time = 12.5
		)

	@BeforeEach
	fun setUp() {
		model = ConflictResolutionListModel()
		panel = ConflictResolutionPanel()
	}

	// ── ConflictResolutionListModel ───────────────────────────────────────────

	@Test
	@DisplayName("model is empty by default")
	fun modelEmptyByDefault() {
		assertThat(model.hasNoResolutions).isTrue()
		assertThat(model.size).isEqualTo(0)
	}

	@Test
	@DisplayName("setResolutions on EDT replaces contents")
	fun setResolutionsOnEdtReplacesContents() {
		SwingUtilities.invokeAndWait {
			model.setResolutions(listOf(holdResolution, speedResolution))
		}

		assertThat(model.size).isEqualTo(2)
		assertThat(model.getElementAt(0)).isEqualTo(holdResolution)
		assertThat(model.getElementAt(1)).isEqualTo(speedResolution)
		assertThat(model.hasNoResolutions).isFalse()
	}

	@Test
	@DisplayName("setResolutions replaces previous contents")
	fun setResolutionsReplacesPreviousContents() {
		SwingUtilities.invokeAndWait {
			model.setResolutions(listOf(holdResolution, speedResolution))
			model.setResolutions(listOf(speedResolution))
		}

		assertThat(model.size).isEqualTo(1)
		assertThat(model.getElementAt(0)).isEqualTo(speedResolution)
	}

	@Test
	@DisplayName("clearResolutions on EDT empties model")
	fun clearResolutionsOnEdtEmptiesModel() {
		SwingUtilities.invokeAndWait {
			model.setResolutions(listOf(holdResolution))
			model.clearResolutions()
		}

		assertThat(model.hasNoResolutions).isTrue()
		assertThat(model.size).isEqualTo(0)
	}

	@Test
	@DisplayName("setResolutions from background thread dispatches to EDT")
	fun setResolutionsFromBackgroundDispatchesToEdt() {
		val latch = CountDownLatch(1)

		Thread {
			model.setResolutions(listOf(holdResolution))
			SwingUtilities.invokeLater { latch.countDown() }
		}.start()

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
		flushEDT()

		assertThat(model.size).isEqualTo(1)
		assertThat(model.hasNoResolutions).isFalse()
	}

	@Test
	@DisplayName("setResolutions with empty list clears model")
	fun setResolutionsWithEmptyListClearsModel() {
		SwingUtilities.invokeAndWait {
			model.setResolutions(listOf(holdResolution))
			model.setResolutions(emptyList())
		}

		assertThat(model.hasNoResolutions).isTrue()
	}

	// ── ConflictResolutionPanel ───────────────────────────────────────────────

	@Test
	@DisplayName("panel is initially invisible and has empty model")
	fun panelInitiallyInvisibleAndEmpty() {
		assertThat(panel.isVisible).isFalse()
		assertThat(panel.listModel.hasNoResolutions).isTrue()
	}

	@Test
	@DisplayName("showResolutions makes panel visible and populates model")
	fun showResolutionsMakesPanelVisibleAndPopulatesModel() {
		val resolutions = listOf(holdResolution, speedResolution)

		panel.showResolutions(conflict, resolutions)
		flushEDT()

		assertThat(panel.isVisible).isTrue()
		assertThat(panel.listModel.size).isEqualTo(2)
	}

	@Test
	@DisplayName("clearResolutions hides panel and empties model")
	fun clearResolutionsHidesPanelAndEmptiesModel() {
		panel.showResolutions(conflict, listOf(holdResolution))
		flushEDT()

		SwingUtilities.invokeAndWait { panel.clearResolutions() }

		assertThat(panel.isVisible).isFalse()
		assertThat(panel.listModel.hasNoResolutions).isTrue()
	}

	@Test
	@DisplayName("clearResolutions invokes onDismiss callback")
	fun clearResolutionsInvokesOnDismissCallback() {
		var dismissed = false
		panel.onDismiss = { dismissed = true }

		panel.showResolutions(conflict, listOf(holdResolution))
		flushEDT()

		SwingUtilities.invokeAndWait { panel.clearResolutions() }

		assertThat(dismissed).isTrue()
	}

	@Test
	@DisplayName("clearResolutions does not throw when onDismiss is null")
	fun clearResolutionsDoesNotThrowWhenOnDismissIsNull() {
		panel.onDismiss = null
		panel.showResolutions(conflict, listOf(holdResolution))
		flushEDT()

		SwingUtilities.invokeAndWait { panel.clearResolutions() }

		assertThat(panel.listModel.hasNoResolutions).isTrue()
	}

	@Test
	@DisplayName("showResolutions from background thread dispatches to EDT")
	fun showResolutionsFromBackgroundDispatchesToEdt() {
		val latch = CountDownLatch(1)

		Thread {
			panel.showResolutions(conflict, listOf(holdResolution, speedResolution))
			SwingUtilities.invokeLater { latch.countDown() }
		}.start()

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
		flushEDT()

		assertThat(panel.isVisible).isTrue()
		assertThat(panel.listModel.size).isEqualTo(2)
	}

	@Test
	@DisplayName("showResolutions with empty list makes panel visible but empty")
	fun showResolutionsWithEmptyListMakesPanelVisible() {
		panel.showResolutions(conflict, emptyList())
		flushEDT()

		assertThat(panel.isVisible).isTrue()
		assertThat(panel.listModel.hasNoResolutions).isTrue()
	}

	private fun flushEDT() {
		SwingUtilities.invokeAndWait { /* flush 1 */ }
		SwingUtilities.invokeAndWait { /* flush 2 */ }
	}
}
