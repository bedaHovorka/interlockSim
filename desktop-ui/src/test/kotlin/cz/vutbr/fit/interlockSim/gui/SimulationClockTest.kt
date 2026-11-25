/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.gui.animation.ControlPanel
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertFailsWith

/**
 * The headless unit tests of [SimulationClock] (PR #1430 coverage): the shown-time ratchet, the
 * per-run tracker attach, the final-paced-time merge and the 10 Hz update timer. The real
 * [ControlPanel] shows the clock, so the tests read [ControlPanel.timeText].
 */
@DisplayName("SimulationClock (headless)")
class SimulationClockTest {
	private val controlPanel = ControlPanel()

	/** A test clock whose paced position the test moves between ticks. */
	private fun newClock(paced: TestablePaced): SimulationClock =
		SimulationClock(controlPanel, pacedTime = { paced.value })

	private class TestablePaced {
		var value = 0.0
	}

	/** The clock text read off the EDT (every test runs its clock calls through [SwingUtilities].) */
	private fun shownTime(): String {
		SwingUtilities.invokeAndWait { /* drain */ }
		return controlPanel.timeText
	}

	/** The clock text read on the EDT, inside a test's own [SwingUtilities.invokeAndWait] block. */
	private fun shownTimeOnEdt(): String = controlPanel.timeText

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("the shown time ratchets: a slower reading never moves it backward")
	fun ratchetNeverMovesBackward() {
		val paced = TestablePaced()
		val clock = newClock(paced)

		SwingUtilities.invokeAndWait {
			clock.refresh()
			assertThatTime(shownTimeOnEdt(), "00:00:00.000")
			paced.value = 2.5
			clock.refresh()
			assertThatTime(shownTimeOnEdt(), "00:00:02.500")
			// A paused runner reports the old pace again; the shown time holds.
			paced.value = 1.0
			clock.refresh()
			assertThatTime(shownTimeOnEdt(), "00:00:02.500")
			// The final paced time of a STOPPED run enters the same ratchet.
			clock.refresh(finalPacedTime = 9.0)
			assertThatTime(shownTimeOnEdt(), "00:00:09.000")
		}
	}

	/** Asserts the formatted clock text at the test's precision. */
	private fun assertThatTime(
		actual: String,
		expected: String
	) {
		assertThat(actual).isEqualTo("Time: $expected")
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("attach gives the run a fresh tracker and detaches the old one from its context")
	fun attachResetsAndSwapsTrackers() {
		val paced = TestablePaced()
		val clock = newClock(paced)
		paced.value = 42.0
		SwingUtilities.invokeAndWait {
			clock.refresh()
			assertThatTime(shownTimeOnEdt(), "00:00:42.000")
		}

		val oldContext = mockk<Context<*, *>>(relaxed = true)
		val newContext = mockk<Context<*, *>>(relaxed = true)

		SwingUtilities.invokeAndWait {
			clock.attach(oldContext)
			clock.attach(newContext) // the new run: the clock starts at zero again

			// The old tracker was detached from the old context; the new one listens to the new one.
			verify(exactly = 1) { oldContext.removePropertyChangeListener(any()) }
			verify(exactly = 1) { newContext.addPropertyChangeListener(any()) }

			// The next refresh shows the new run's reset: the ratchet restarted at zero.
			paced.value = 0.0
			clock.refresh()
			assertThatTime(shownTimeOnEdt(), "00:00:00.000")
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("a null context detaches the tracker; the clock keeps its paced reading")
	fun nullContextDetachesTracker() {
		val paced = TestablePaced()
		val clock = newClock(paced)
		val context = mockk<Context<*, *>>(relaxed = true)

		SwingUtilities.invokeAndWait {
			clock.attach(context)
			clock.attach(null)
			verify(exactly = 1) { context.removePropertyChangeListener(any()) }

			paced.value = 3.0
			clock.refresh()
			assertThatTime(shownTimeOnEdt(), "00:00:03.000")
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("startUpdates shows the paced time at 10 Hz; stopUpdates ends the ticking")
	fun timerFollowsThePace() {
		val paced = TestablePaced()
		val clock = newClock(paced)

		SwingUtilities.invokeAndWait { clock.startUpdates() }
		SwingUtilities.invokeAndWait { paced.value = 1.5 }
		Thread.sleep(300) // let at least one 100 ms tick fire

		assertThat(shownTime()).isEqualTo("Time: 00:00:01.500")

		SwingUtilities.invokeAndWait { clock.stopUpdates() }
		val frozen = shownTime()
		paced.value = 8.0
		Thread.sleep(250)

		assertThat(shownTime()).isEqualTo(frozen)

		SwingUtilities.invokeAndWait { clock.stopUpdates() } // repeatable
		assertThat(shownTime()).isEqualTo(frozen)
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("startUpdates must be called from the EDT")
	fun startUpdatesRequiresTheEdt() {
		val clock = newClock(TestablePaced())

		assertFailsWith<IllegalArgumentException> { clock.startUpdates() }

		SwingUtilities.invokeAndWait { clock.stopUpdates() }
		assertThatTime(shownTime(), "00:00:00.000") // untouched by the failed start
	}
}
