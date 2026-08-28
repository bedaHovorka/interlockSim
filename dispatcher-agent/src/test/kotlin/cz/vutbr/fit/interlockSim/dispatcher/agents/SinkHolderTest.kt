/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SinkHolder] (SP2c.6, Issue #829).
 *
 * Covers the per-cycle emission counter that guards the Path A double-dispatch regression
 * (see [SinkHolder] class KDoc): [emit] delegates to [SinkHolder.current] and increments the
 * counter; [actedThisCycle] / [resetCycleEmissionCount] bracket one LLM cycle; the default sink is
 * [EmittedActionSink.NO_OP]; and swapping [SinkHolder.current] re-routes subsequent emissions.
 *
 * @since Issue #829 (SP2c.6 — Goal 10)
 */
@DisplayName("SinkHolder — per-cycle emission counter and sink swap")
class SinkHolderTest {
	@Test
	@DisplayName("default current sink is EmittedActionSink.NO_OP")
	fun defaultCurrentIsNoOp() {
		val holder = SinkHolder()
		assertThat(holder.current).isSameInstanceAs(EmittedActionSink.NO_OP)
	}

	@Test
	@DisplayName("emit delegates to current and records the emission")
	fun emitDelegatesToCurrentAndCounts() {
		val received = mutableListOf<DispatchAction>()
		val holder = SinkHolder(EmittedActionSink { received.add(it) })

		holder.emit(DispatchAction.ApproveTrain("T-1"))
		holder.emit(DispatchAction.NoOp)

		assertThat(received).containsExactly(
			DispatchAction.ApproveTrain("T-1"),
			DispatchAction.NoOp
		)
		assertThat(holder.actedThisCycle()).isTrue()
	}

	@Test
	@DisplayName("actedThisCycle is false before any emit and after resetCycleEmissionCount")
	fun actedThisCycleReflectsReset() {
		val holder = SinkHolder(EmittedActionSink { })

		assertThat(holder.actedThisCycle()).isFalse()

		holder.emit(DispatchAction.NoOp)
		assertThat(holder.actedThisCycle()).isTrue()

		holder.resetCycleEmissionCount()
		assertThat(holder.actedThisCycle()).isFalse()
	}

	@Test
	@DisplayName("resetCycleEmissionCount zeroes the counter so multiple emissions across cycles are tracked per-cycle")
	fun resetAllowsPerCycleTracking() {
		val holder = SinkHolder(EmittedActionSink { })

		// Cycle 1: two emissions.
		holder.emit(DispatchAction.ApproveTrain("T-1"))
		holder.emit(DispatchAction.NoOp)
		assertThat(holder.actedThisCycle()).isTrue()

		// Reset before cycle 2.
		holder.resetCycleEmissionCount()
		assertThat(holder.actedThisCycle()).isFalse()

		// Cycle 2: one emission — counter reflects only this cycle, not the cumulative three.
		holder.emit(DispatchAction.ApproveTrain("T-2"))
		assertThat(holder.actedThisCycle()).isTrue()
	}

	@Test
	@DisplayName("counting a NoOp emission as acted is correct (no_op is a deliberate decision)")
	fun noOpEmissionCountsAsActed() {
		// SP2c.19 / #829: a NoOp is a real decision by the LLM, so the fallback must not run on
		// top of it. A bare NoOp emission must therefore register as "acted this cycle".
		val holder = SinkHolder(EmittedActionSink { })
		holder.emit(DispatchAction.NoOp)
		assertThat(holder.actedThisCycle()).isTrue()
	}

	@Test
	@DisplayName("swapping current re-routes subsequent emissions to the new sink")
	fun swappingCurrentReRoutesEmissions() {
		val first = mutableListOf<DispatchAction>()
		val second = mutableListOf<DispatchAction>()
		val holder = SinkHolder(EmittedActionSink { first.add(it) })

		holder.emit(DispatchAction.ApproveTrain("T-1"))
		assertThat(first).containsExactly(DispatchAction.ApproveTrain("T-1"))
		assertThat(second).isEmpty()

		// Swap the active sink mid-life — e.g. the tick controller installing a list-collecting sink.
		holder.current = EmittedActionSink { second.add(it) }

		holder.emit(DispatchAction.NoOp)
		assertThat(first).containsExactly(DispatchAction.ApproveTrain("T-1")) // unchanged
		assertThat(second).containsExactly(DispatchAction.NoOp)
		assertThat(holder.actedThisCycle()).isTrue()
	}

	@Test
	@DisplayName("NO_OP default sink discards emissions while still counting them")
	fun noOpDefaultDiscardsButCounts() {
		// The default NO_OP sink must not throw and must discard, but the counter still records the
		// emission — this is the shape KoogAgentPlanAdapterTest relies on (a bare SinkHolder with
		// NO_OP detects a tool emission without any real sink wired).
		val holder = SinkHolder()
		holder.emit(DispatchAction.ApproveTrain("T-1"))
		holder.emit(DispatchAction.NoOp)

		assertThat(holder.actedThisCycle()).isTrue()
		assertThat(holder.current).isSameInstanceAs(EmittedActionSink.NO_OP)
	}

	@Test
	@DisplayName("actedThisCycle reflects only the post-reset window, not the lifetime emit count")
	fun actedThisCycleIsPostResetWindow() {
		val holder = SinkHolder(EmittedActionSink { })
		holder.emit(DispatchAction.ApproveTrain("T-1"))
		holder.emit(DispatchAction.ApproveTrain("T-2"))
		holder.resetCycleEmissionCount()

		// After reset, no emission in the new cycle yet → not acted, even though two emits happened
		// earlier in the holder's lifetime.
		assertThat(holder.actedThisCycle()).isFalse()
		assertThat(emissionCountIsZero(holder)).isEqualTo(true)
	}

	/**
	 * Reads the post-reset state via the public API: after [resetCycleEmissionCount] with no
	 * subsequent [emit], [actedThisCycle] is false — the helper exists only to make the assertion
	 * read as "the counter is zero" without exposing the private field.
	 */
	private fun emissionCountIsZero(holder: SinkHolder): Boolean = !holder.actedThisCycle()
}
