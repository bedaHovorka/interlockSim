/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the per-cycle action cap and emission log added to [SinkHolder] (SP2c.24, #847).
 *
 * @since Issue #847 (SP2c.24 — parameter grid; `maxActionsPerTick` made live)
 */
@DisplayName("SP2c.24 — SinkHolder per-cycle action cap (#847)")
class SinkHolderActionCapTest {
	private class CollectingSink : EmittedActionSink {
		val emitted = mutableListOf<DispatchAction>()

		override fun emit(action: DispatchAction) {
			emitted += action
		}
	}

	private fun holderOf(cap: Int): Pair<SinkHolder, CollectingSink> {
		val sink = CollectingSink()
		return SinkHolder(sink, maxActionsPerTick = cap) to sink
	}

	@Test
	fun `tryEmit accepts exactly maxActionsPerTick non-NoOp actions`() {
		val (holder, sink) = holderOf(2)

		assertThat(holder.tryEmit(DispatchAction.ApproveTrain("Train #1"))).isTrue()
		assertThat(holder.tryEmit(DispatchAction.ApproveTrain("Train #2"))).isTrue()
		assertThat(holder.tryEmit(DispatchAction.ApproveTrain("Train #3"))).isFalse()

		assertThat(sink.emitted.size).isEqualTo(2)
	}

	@Test
	@DisplayName("a refused action never reaches the sink — the cap protects the queue, not just the count")
	fun refusedActionIsNotEmitted() {
		val (holder, sink) = holderOf(1)
		holder.tryEmit(DispatchAction.ApproveTrain("Train #1"))
		holder.tryEmit(DispatchAction.CancelRoute("Train #2"))

		assertThat(sink.emitted).containsExactly(DispatchAction.ApproveTrain("Train #1"))
	}

	@Test
	@DisplayName("NoOp is exempt from the cap, mirroring ActionValidator.validateBatch")
	fun noOpIsExempt() {
		// C6: `no_op` is a first-class decision. Capping it would make "do nothing" fail on a
		// busy tick, which is exactly when the model most needs to be able to say it.
		val (holder, sink) = holderOf(1)
		holder.tryEmit(DispatchAction.ApproveTrain("Train #1"))

		assertThat(holder.tryEmit(DispatchAction.NoOp)).isTrue()
		assertThat(holder.tryEmit(DispatchAction.NoOp)).isTrue()
		assertThat(sink.emitted.size).isEqualTo(3)
		assertThat(holder.actionsThisCycle()).isEqualTo(1)
	}

	@Test
	fun `resetCycleEmissionCount restores the full budget for the next cycle`() {
		val (holder, _) = holderOf(1)
		holder.tryEmit(DispatchAction.ApproveTrain("Train #1"))
		assertThat(holder.tryEmit(DispatchAction.ApproveTrain("Train #2"))).isFalse()

		holder.resetCycleEmissionCount()

		assertThat(holder.tryEmit(DispatchAction.ApproveTrain("Train #2"))).isTrue()
	}

	@Test
	@DisplayName("emittedActionsThisCycle records what was emitted, in order, and clears on reset")
	fun emissionLog() {
		val (holder, _) = holderOf(3)
		holder.tryEmit(DispatchAction.ApproveTrain("Train #1"))
		holder.tryEmit(DispatchAction.RequestRoute("Train #1", "doB1", "B"))

		assertThat(holder.emittedActionsThisCycle())
			.containsExactly(
				DispatchAction.ApproveTrain("Train #1"),
				DispatchAction.RequestRoute("Train #1", "doB1", "B")
			)

		holder.resetCycleEmissionCount()
		assertThat(holder.emittedActionsThisCycle()).isEmpty()
	}

	@Test
	@DisplayName("actedThisCycle still reflects a deliberate NoOp, so the fallback stays suppressed")
	fun noOpStillCountsAsActed() {
		val (holder, _) = holderOf(3)
		holder.tryEmit(DispatchAction.NoOp)

		assertThat(holder.actedThisCycle()).isTrue()
	}
}
