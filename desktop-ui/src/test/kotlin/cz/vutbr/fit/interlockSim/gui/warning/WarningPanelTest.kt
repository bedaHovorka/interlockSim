/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for WarningListModel and WarningPanel (Issue #616, Goal 3 SP6)
*/

package cz.vutbr.fit.interlockSim.gui.warning

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Tests for [WarningListModel] and [WarningPanel].
 *
 * Covers EDT-safe mutations, [WarningListModel.hasCriticalWarning] query,
 * and [WarningPanel.clearWarnings].
 */
@DisplayName("WarningListModel and WarningPanel")
class WarningPanelTest {
	private lateinit var model: WarningListModel
	private lateinit var panel: WarningPanel

	private val reservation =
		CollisionWarning.ReservationConflict(
			trainId = "Train-A",
			conflictingTrainId = "Train-B",
			time = 10.0
		)

	private val violation =
		CollisionWarning.BlockEntryViolation(
			trainId = "Train-A",
			block = mockk(relaxed = true),
			time = 15.0
		)

	@BeforeEach
	fun setUp() {
		model = WarningListModel()
		panel = WarningPanel()
	}

	// ── WarningListModel ──────────────────────────────────────────────────────

	@Test
	@DisplayName("model is empty by default")
	fun modelEmptyByDefault() {
		assertThat(model.isEmpty).isTrue()
		assertThat(model.hasCriticalWarning()).isFalse()
	}

	@Test
	@DisplayName("addWarning on EDT adds element to model")
	fun addWarningOnEdtAddsElement() {
		SwingUtilities.invokeAndWait { model.addWarning(reservation) }

		assertThat(model.size()).isEqualTo(1)
		assertThat(model.getElementAt(0)).isEqualTo(reservation)
		assertThat(model.hasCriticalWarning()).isTrue()
	}

	@Test
	@DisplayName("addWarning from background thread dispatches to EDT")
	fun addWarningFromBackgroundDispatchesToEdt() {
		val latch = CountDownLatch(1)

		Thread {
			model.addWarning(violation)
			// Wait until EDT processes the invokeLater
			SwingUtilities.invokeLater { latch.countDown() }
		}.start()

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
		flushEDT()

		assertThat(model.size()).isEqualTo(1)
		assertThat(model.hasCriticalWarning()).isTrue()
	}

	@Test
	@DisplayName("clearWarnings on EDT empties model")
	fun clearWarningsOnEdtEmptiesModel() {
		SwingUtilities.invokeAndWait {
			model.addWarning(reservation)
			model.clearWarnings()
		}

		assertThat(model.isEmpty).isTrue()
		assertThat(model.hasCriticalWarning()).isFalse()
	}

	@Test
	@DisplayName("multiple warnings are accumulated in insertion order")
	fun multipleWarningsAccumulated() {
		SwingUtilities.invokeAndWait {
			model.addWarning(reservation)
			model.addWarning(violation)
		}

		assertThat(model.size()).isEqualTo(2)
		assertThat(model.getElementAt(0)).isEqualTo(reservation)
		assertThat(model.getElementAt(1)).isEqualTo(violation)
	}

	// ── WarningPanel ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("panel is initially empty")
	fun panelInitiallyEmpty() {
		assertThat(panel.listModel.isEmpty).isTrue()
		assertThat(panel.hasCriticalWarning()).isFalse()
	}

	@Test
	@DisplayName("addWarning on panel delegates to listModel")
	fun addWarningDelegatesToModel() {
		panel.addWarning(reservation)
		flushEDT()

		assertThat(panel.listModel.size()).isEqualTo(1)
		assertThat(panel.hasCriticalWarning()).isTrue()
	}

	@Test
	@DisplayName("clearWarnings on panel empties listModel")
	fun clearWarningsEmptiesModel() {
		panel.addWarning(reservation)
		flushEDT()

		SwingUtilities.invokeAndWait { panel.clearWarnings() }

		assertThat(panel.listModel.isEmpty).isTrue()
		assertThat(panel.hasCriticalWarning()).isFalse()
	}

	private fun flushEDT() {
		SwingUtilities.invokeAndWait { /* flush 1 */ }
		SwingUtilities.invokeAndWait { /* flush 2 */ }
	}
}
