/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.cells.createConstantInstance
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import cz.vutbr.fit.interlockSim.objects.core.Cell
import kotlin.test.Test

/**
 * Direct coverage of the `internal` [separatorAspect] / [separatorName] perception mapping
 * (Issue #552 / SP2a.1), with emphasis on the Issue #812 direction-aware display fix.
 *
 * [separatorAspect] is the **train's own next-separator** perception: [sep] is always a
 * separator on the train's own reserved path (the train is the reservation holder), so the
 * mapping returns the raw `signal` — proceed-when-lit — and deliberately does NOT apply any
 * direction guard. In particular, a semaphore cleared for the reverse direction still reports
 * its proceed aspect here (the reverse-travelling holder is authorized). The display-truthfulness
 * fix lives in the global views (canvas, LLM `all_signal_aspects`) instead; see
 * [DynamicRailSemaphore.authorizedDirection].
 */
class PerceptionMappingTest {
	// orientation=true, HORIZONTAL → direction() = A, anti(A) = F
	private fun freshSemaphore(): cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore =
		createDynamicInstance(RailSemaphore(true, Cell.SpatialType.HORIZONTAL))

	@Test
	fun `separatorAspect returns the raw signal for a forward-lit semaphore`() {
		val sem = freshSemaphore()
		sem.setUpSpeed(Cell.Segment.F, Cell.Segment.A, 20.0)
		assertThat(sem.signal.isAllowing()).isTrue()

		// Holder's view: raw signal, no direction guard.
		assertThat(separatorAspect(sem)).isEqualTo(sem.signal)
		assertThat(separatorAspect(sem)?.isAllowing()).isEqualTo(true)
	}

	@Test
	fun `separatorAspect returns the raw signal for a reverse-lit semaphore`() {
		val sem = freshSemaphore()
		// Reverse pairing (A → F): a proceed aspect cleared for the opposite direction.
		sem.setUpSpeed(Cell.Segment.A, Cell.Segment.F, 20.0)
		assertThat(sem.signal.isAllowing()).isTrue()

		// The reverse-travelling holder is authorized: separatorAspect must still report the
		// proceed aspect (raw), NOT STOP. A static-orientation or stored-direction guard applied
		// here would incorrectly show STOP to the holder at its own semaphore.
		assertThat(separatorAspect(sem)).isEqualTo(sem.signal)
		assertThat(separatorAspect(sem)?.isAllowing()).isEqualTo(true)
		// Sanity: this is the same semaphore whose forward-facing canvas view would be STOP.
		assertThat(sem.isAllowingFor(Cell.Segment.F, Cell.Segment.A)).isFalse()
		assertThat(sem.isAllowingFor(Cell.Segment.A, Cell.Segment.F)).isTrue()
	}

	@Test
	fun `separatorAspect returns STOP for a STOP semaphore`() {
		val sem = freshSemaphore()
		assertThat(sem.signal).isEqualTo(Signal.STOP)
		assertThat(separatorAspect(sem)).isEqualTo(Signal.STOP)
		assertThat(separatorAspect(sem)?.isAllowing()).isEqualTo(false)
	}

	@Test
	fun `separatorAspect returns the outSemaphore signal for a DynamicInOut`() {
		// InOut("X", true, HORIZONTAL): outSemaphore faces direction() (orientation=true → A).
		// Build a DynamicInOut with a constant-FREE outSemaphore (the predzvěst/narážník case)
		// and a dynamic inSemaphore.
		val static = InOut("X", true, Cell.SpatialType.HORIZONTAL)
		val outSemaphore = createConstantInstance(static.getOutSemaphore(), Signal.FREE)
		val inSemaphore = createDynamicInstance(static.getInSemaphore())
		val dynamicInOut = DynamicInOut(static, inSemaphore, outSemaphore)

		assertThat(separatorAspect(dynamicInOut)).isEqualTo(Signal.FREE)
		assertThat(separatorAspect(dynamicInOut)?.isAllowing()).isEqualTo(true)
	}

	@Test
	fun `separatorAspect returns null for a null separator`() {
		assertThat(separatorAspect(null)).isNull()
	}

	@Test
	fun `separatorName returns the name for a DynamicRailSemaphore`() {
		val sem = freshSemaphore()
		// DynamicRailSemaphore.name derives from the static RailSemaphore, which is unnamed here.
		assertThat(separatorName(sem)).isNull()
	}

	@Test
	fun `separatorName returns the name for a DynamicInOut`() {
		val static = InOut("X", true, Cell.SpatialType.HORIZONTAL)
		val outSemaphore = createConstantInstance(static.getOutSemaphore(), Signal.FREE)
		val inSemaphore = createDynamicInstance(static.getInSemaphore())
		val dynamicInOut = DynamicInOut(static, inSemaphore, outSemaphore)

		assertThat(separatorName(dynamicInOut)).isEqualTo("X")
	}

	@Test
	fun `separatorName returns null for a null separator`() {
		assertThat(separatorName(null)).isNull()
	}
}
