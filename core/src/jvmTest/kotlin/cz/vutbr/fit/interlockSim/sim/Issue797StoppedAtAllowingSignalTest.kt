/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Regression test for Issue #797 — a train frozen in front of an allowing signal.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.runShuntingLoop
import cz.vutbr.fit.interlockSim.util.Util
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * Regression test for Issue #797: the canonical `shuntingLoop` deadlocked at t≈683 because
 * Train #16 came to rest at the `zA` block boundary and was never woken again — even though
 * its route onto the free second track `k2` was fully reserved, switch `vA` was configured to
 * `BRANCH`, and `zA` had been switched from `STOP` to `S80` at t=662 and never reverted.
 *
 * ## Root cause
 *
 * Commit `0c38a757` (Issue #750 step 1) converted the block-boundary gate in
 * [Train]'s `Site.actions()` from a **level-triggered** `waitUntil { position + dtMin >= length }`
 * to an **edge-triggered** `waitCrossing { boundaryThreshold - position }`.
 *
 * kDisco re-tests a `waitUntil` notice after every discrete event and after every accepted
 * integration step, so a train parked arbitrarily close to the boundary is always released.
 * A `waitCrossing` notice is sampled only across integration-step endpoints and requires a
 * strict sign change; the train's braking law `a = -v² / (2s)` drives `v → 0` as `s → 0`, so
 * once the crossing is missed the position never changes again and the notice can never fire.
 * The `Site` coroutine is then parked forever, and because `separatorAction` / `semaphoreAction`
 * / the path re-query all sit *downstream* of that gate, the train emits nothing for the rest
 * of the run.
 *
 * ## What this test asserts
 *
 * A safety invariant rather than a baseline constant: **no train may be at a standstill while
 * the signal ahead of it is showing an allowing aspect.** That is precisely the observed
 * symptom and it holds for any topology and any dispatcher, so it does not need re-measuring
 * when timings shift.
 *
 * A second, coarser assertion guards against the run silently stalling in some other way.
 *
 * @see docs/TRAIN_PASSIVATION_FIX.md for the earlier, unrelated creep-on-passivation defect.
 */
@Tag("integration-test")
@DisplayName("Issue #797 — no train may stand still in front of an allowing signal")
class Issue797StoppedAtAllowingSignalTest : KoinTestBase() {
	private companion object {
		/**
		 * Long enough to reach the first simultaneous approval of two opposing trains, which
		 * happens at t=658 with the fixed-seed generator and is what triggers the deadlock.
		 */
		const val END_TIME: Long = 1024L

		/**
		 * Trains fully exited in the deadlocked run. The pre-fix run parks at exactly 14 and
		 * then makes no further progress for ~341s, so any value above this proves the second
		 * half of the run was not lost to a standstill. Deliberately a lower bound, not an
		 * equality — the exact figure is pinned by [ShuntingLoopHeavyTest].
		 */
		const val DEADLOCKED_TRAINS_EXITED: Int = 14

		/** A train slower than this is treated as standing still. */
		const val STANDSTILL_VELOCITY_MPS: Double = 1e-6
	}

	private val factory: SimulationContextFactory by inject()

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("shuntingLoop(1024s) leaves no train stopped at an allowing signal")
	fun noTrainIsLeftStoppedInFrontOfAnAllowingSignal() {
		val context =
			TestFixtures.loadShuntingXml().use { stream ->
				Util.assertInstanceOf<DefaultSimulationContext>(factory.createContext(stream))
			}
		context.use { ctx ->
			val loop = runShuntingLoop(ctx, END_TIME)

			val frozenAtGreen =
				loop.getApprovedTrains().filter { train ->
					train.getVelocity() < STANDSTILL_VELOCITY_MPS &&
						train.signalAheadAspect?.isAllowing() == true
				}

			assertThat(
				frozenAtGreen.map { "Train #${it.getNumber()} at ${it.signalAheadName}=${it.signalAheadAspect}" },
				name = "trains standing still in front of an allowing signal"
			).isEmpty()

			assertThat(loop.getTrainsExited(), name = "trains exited")
				.isGreaterThan(DEADLOCKED_TRAINS_EXITED)

			assertNoTrainIsUnaccountedFor(loop)
		}
	}

	/**
	 * Train-accounting identity.
	 *
	 * `getTrainsEntered()` counts trains **generated into the queue** (`InnerGenerator.placeTrain`),
	 * not trains that entered the network, so `entered - exited` is legitimately larger than
	 * [RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS]: it also includes every train still
	 * waiting in the queue for a free slot. What must always hold is that each generated train is
	 * in exactly one of three states — exited, approved (in the network), or still queued — and
	 * that the approved set never exceeds the dispatcher's concurrency limit.
	 *
	 * Asserting this pins down the counter semantics so a future stall cannot hide as a train that
	 * is neither running nor accounted for.
	 */
	private fun assertNoTrainIsUnaccountedFor(loop: ShuntingLoop) {
		val approved = loop.getApprovedTrains().size
		val queued = loop.getQueuedTrains().size

		assertThat(approved, name = "approved (in-network) trains")
			.isLessThanOrEqualTo(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS)

		assertThat(loop.getTrainsExited() + approved + queued, name = "exited + approved + queued")
			.isEqualTo(loop.getTrainsEntered())
	}
}
