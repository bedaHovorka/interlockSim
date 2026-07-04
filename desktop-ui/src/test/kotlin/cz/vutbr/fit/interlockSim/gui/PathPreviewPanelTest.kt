/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.paths.Route
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.Container
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.SwingUtilities

/**
 * Unit tests for [PathPreviewPanel] (Issue #653: background route search).
 *
 * All Swing state is read and mutated on the EDT via [SwingUtilities.invokeAndWait]
 * so the tests run safely in headless mode. Route searches run on a [javax.swing.SwingWorker]
 * background thread, so result assertions wait for the status label to leave the
 * "Searching…" state (or for a callback latch) with a timeout.
 */
@DisplayName("PathPreviewPanel")
class PathPreviewPanelTest : KoinTestBase() {
	private lateinit var panel: PathPreviewPanel

	@BeforeEach
	fun createPanel() {
		SwingUtilities.invokeAndWait {
			panel = PathPreviewPanel()
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private fun findButton(text: String): JButton =
		findAllComponents(panel, JButton::class.java)
			.firstOrNull { it.text == text }
			?: error("JButton with text '$text' not found in PathPreviewPanel")

	/**
	 * The status label is the last JLabel directly in the top bar (after "From:" and "To:").
	 * Deep traversal would also match the JList cell-renderer label, so only direct
	 * children of the top bar are considered.
	 */
	private fun statusLabel(): JLabel {
		val topBar = panel.getComponent(0) as Container
		return topBar.components.filterIsInstance<JLabel>().lastOrNull()
			?: error("Status JLabel not found in PathPreviewPanel top bar")
	}

	private fun routeList(): JList<*> =
		findAllComponents(panel, JList::class.java).firstOrNull()
			?: error("JList not found in PathPreviewPanel")

	/** Combo boxes in insertion order: index 0 = From, index 1 = To. */
	private fun combos(): List<JComboBox<*>> = findAllComponents(panel, JComboBox::class.java)

	/** Recursively collect all components of [type] in the container hierarchy. */
	private fun <T> findAllComponents(
		container: Container,
		type: Class<T>
	): List<T> {
		val result = mutableListOf<T>()
		for (c in container.components) {
			if (type.isInstance(c)) {
				@Suppress("UNCHECKED_CAST")
				result.add(c as T)
			}
			if (c is Container) {
				result.addAll(findAllComponents(c, type))
			}
		}
		return result
	}

	private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

	/** Poll [condition] on the EDT until it holds or [timeoutMs] elapses. */
	private fun awaitOnEdt(
		timeoutMs: Long = 5_000,
		condition: () -> Boolean
	) {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (System.currentTimeMillis() < deadline) {
			var met = false
			SwingUtilities.invokeAndWait { met = condition() }
			if (met) return
			Thread.sleep(POLL_INTERVAL_MS)
		}
		error("Condition not met within $timeoutMs ms (status: '${statusLabel().text}')")
	}

	/** Mocked context with two InOuts and a stubbed [RouteFinder]. */
	private fun mockContext(finder: RouteFinder): EditingContext {
		val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
		val inB = InOut("B", false, Cell.SpatialType.HORIZONTAL)
		val ctx = mockk<EditingContext>(relaxed = true)
		every { ctx.getInOuts() } returns listOf(inA, inB)
		every { ctx.getRouteFinder() } returns finder
		return ctx
	}

	// ── setEditingContext ─────────────────────────────────────────────────────

	@Test
	@DisplayName("setEditingContext populates combos and enables buttons")
	fun setEditingContext_populatesCombosAndEnablesButtons() {
		TestTopologies.simpleLinearPath().use { ctx ->
			onEdt { panel.setEditingContext(ctx) }

			onEdt {
				val (from, to) = combos()
				assertThat(from.itemCount).isEqualTo(2)
				assertThat(to.itemCount).isEqualTo(2)
				assertThat(from.selectedIndex).isEqualTo(0)
				assertThat(to.selectedIndex).isEqualTo(1)
				assertThat(findButton("Find").isEnabled).isTrue()
				assertThat(findButton("Clear").isEnabled).isTrue()
			}
		}
	}

	@Test
	@DisplayName("setEditingContext(null) clears combos and disables buttons")
	fun setEditingContextNull_clearsCombosAndDisablesButtons() {
		TestTopologies.simpleLinearPath().use { ctx ->
			onEdt { panel.setEditingContext(ctx) }
			onEdt { panel.setEditingContext(null) }

			onEdt {
				val (from, to) = combos()
				assertThat(from.itemCount).isEqualTo(0)
				assertThat(to.itemCount).isEqualTo(0)
				assertThat(findButton("Find").isEnabled).isFalse()
				assertThat(findButton("Clear").isEnabled).isFalse()
			}
		}
	}

	// ── Find: success ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("Find populates route list, auto-selects first route and fires onRouteSelected")
	fun find_success_populatesListAndFiresOnRouteSelected() {
		TestTopologies.simpleLinearPath().use { ctx ->
			val selected = CountDownLatch(1)
			var selectedRoutes: List<Route> = emptyList()
			var selectedIndex = -1

			onEdt {
				panel.setEditingContext(ctx)
				panel.onRouteSelected = { routes, index ->
					selectedRoutes = routes
					selectedIndex = index
					selected.countDown()
				}
				findButton("Find").doClick()
			}

			assertThat(selected.await(5, TimeUnit.SECONDS)).isTrue()
			awaitOnEdt { statusLabel().text != "Searching…" }

			onEdt {
				assertThat(statusLabel().text).isEqualTo("1 route(s) found.")
				assertThat(routeList().model.size).isEqualTo(1)
				assertThat(routeList().selectedIndex).isEqualTo(0)
				assertThat(routeList().model.getElementAt(0) as String).contains("Route 1")
				assertThat(findButton("Find").isEnabled).isTrue()
			}
			assertThat(selectedIndex).isEqualTo(0)
			assertThat(selectedRoutes.size).isEqualTo(1)
		}
	}

	// ── Find: empty result ────────────────────────────────────────────────────

	@Test
	@DisplayName("Find with no route shows 'No path found.' and fires onClear")
	fun find_noRoute_showsNoPathFoundAndFiresOnClear() {
		val finder = mockk<RouteFinder>()
		every { finder.findRoutes(any(), any(), any(), any(), any()) } returns emptyList()
		val cleared = CountDownLatch(1)

		onEdt {
			panel.setEditingContext(mockContext(finder))
			panel.onClear = { cleared.countDown() }
			findButton("Find").doClick()
		}

		assertThat(cleared.await(5, TimeUnit.SECONDS)).isTrue()
		awaitOnEdt { statusLabel().text != "Searching…" }

		onEdt {
			assertThat(statusLabel().text).isEqualTo("No path found.")
			assertThat(routeList().model.size).isEqualTo(0)
			assertThat(findButton("Find").isEnabled).isTrue()
			assertThat(findButton("Clear").isEnabled).isTrue()
		}
	}

	// ── Find: failure ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("Find failure shows 'Path finding error.' and re-enables Find")
	fun find_failure_showsErrorAndReenablesFind() {
		val finder = mockk<RouteFinder>()
		every { finder.findRoutes(any(), any(), any(), any(), any()) } throws IllegalStateException("boom")

		onEdt {
			panel.setEditingContext(mockContext(finder))
			findButton("Find").doClick()
		}

		awaitOnEdt { statusLabel().text != "Searching…" }

		onEdt {
			assertThat(statusLabel().text).isEqualTo("Path finding error.")
			assertThat(routeList().model.size).isEqualTo(0)
			assertThat(findButton("Find").isEnabled).isTrue()
		}
	}

	// ── Find: missing selection ───────────────────────────────────────────────

	@Test
	@DisplayName("Find without source/target selection shows hint and stays enabled")
	fun find_withoutSelection_showsHint() {
		val finder = mockk<RouteFinder>()
		val ctx = mockk<EditingContext>(relaxed = true)
		every { ctx.getInOuts() } returns emptyList()
		every { ctx.getRouteFinder() } returns finder

		onEdt {
			panel.setEditingContext(ctx)
			findButton("Find").doClick()
		}

		onEdt {
			assertThat(statusLabel().text).isEqualTo("Select source and target first.")
			assertThat(findButton("Find").isEnabled).isTrue()
		}
	}

	// ── Clear ─────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("Clear resets route list, selection and status, and fires onClear")
	fun clear_resetsResultsAndFiresOnClear() {
		TestTopologies.simpleLinearPath().use { ctx ->
			val selected = CountDownLatch(1)
			val cleared = CountDownLatch(1)

			onEdt {
				panel.setEditingContext(ctx)
				panel.onRouteSelected = { _, _ -> selected.countDown() }
				panel.onClear = { cleared.countDown() }
				findButton("Find").doClick()
			}
			assertThat(selected.await(5, TimeUnit.SECONDS)).isTrue()

			onEdt { findButton("Clear").doClick() }

			assertThat(cleared.await(5, TimeUnit.SECONDS)).isTrue()
			onEdt {
				assertThat(statusLabel().text).isEqualTo(" ")
				assertThat(routeList().model.size).isEqualTo(0)
				assertThat(routeList().selectedIndex).isEqualTo(-1)
			}
		}
	}

	companion object {
		private const val POLL_INTERVAL_MS = 20L
	}
}
