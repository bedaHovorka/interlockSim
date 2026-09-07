/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.StaleTailReclaimHarness
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Issue #1025: reclaiming a stale route tail must never kill the simulation thread.
 *
 * ## What this pins
 *
 * `OrphanReservationSweeper.evaluateOccupyingTrain` reclaims the un-travelled RESERVED tail of a
 * train that stands on the rest of its own route, through `RegistryPartialRouteReleaser`. That
 * releaser drives every governing semaphore to STOP first (#893 task A3) and only then moves each
 * tail block RESERVED → FREE. It does **not** touch the train's `PathInfo`: per-block release
 * keeps `trainToPathInfo` by explicit design (`PathReservationRegistry.unregisterBlock`), and
 * `DefaultTrainNavigationService` never reads block state.
 *
 * The concern behind #1025 is that a train could therefore still be routed into a block that was
 * freed under it, and `DynamicTrackBlock.enter` asserts RESERVED → OCCUPIED. Entering a FREE block
 * raises `SimulationException[FATAL]` on the kDisco simulation thread, which no caller catches.
 *
 * ## Why the threshold is 2 s and not the shipped 60 s
 *
 * The reclaim is what is under test, not the staleness policy. At the shipped
 * `DEFAULT_STALE_AFTER_SIM_SECONDS` a 300 s run reclaims nothing, and the test would assert
 * against a code path that never ran. At 2 s the reclaim fires within the first few control
 * steps, which is exactly the situation to be safe in.
 *
 * The assertion on the reclaim count is what keeps this test honest: without it, a future change
 * that stopped reclaiming anything at all would leave the test passing for the wrong reason.
 *
 * ## What this does and does not reproduce
 *
 * This harness does **not** reproduce the #1025 FATAL by itself: with a 2 s threshold the reclaim
 * lands long before a train reaches the tail's signal, so the train stalls on `OwnershipConflict`
 * instead of entering a FREE block. The FATAL needs the reclaim to land inside the one second
 * between a train reading a proceed aspect and booking the block — that window is reproduced
 * deterministically by [Issue1025CommittedTrainReleaseTest], and the registry/physical divergence
 * a rollback can leave behind by
 * `cz.vutbr.fit.interlockSim.context.navigation.ReservePathConflictRollbackScopeTest` in `:core`.
 * [Issue1025StaleTailReleaseHeavyTest] repeats this run as a manual soak.
 */
@DisplayName("Issue #1025 — reclaiming a stale tail must not kill the simulation")
@Tag("integration-test")
class Issue1025StaleTailReleaseTest : DispatcherKoinTestBase() {
	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	fun `a reclaimed stale tail leaves the simulation thread alive`() {
		// The run itself is the assertion for thread survival: a FATAL on the simulation thread
		// propagates out of run() and fails the test.
		val outcome =
			StaleTailReclaimHarness.run(
				context = TestFixtures.newShuntingSimulationContext().tracked(),
				simEndTime = SIM_END_TIME,
				staleAfterSimSeconds = AGGRESSIVE_STALE_SECONDS
			)

		assertThat(outcome.partialReleaseCount, name = "un-travelled tails actually reclaimed")
			.isGreaterThan(0)
		assertThat(outcome.barrierTimedOut)
			.withMessage("the sim thread must never wait out the driver barrier")
			.isFalse()
		assertThat(outcome.driverFailure)
			.withMessage("the driver thread must complete every cycle without throwing")
			.isNull()
	}

	companion object {
		const val SIM_END_TIME = 300L

		/** Aggressive on purpose — see the KDoc. */
		const val AGGRESSIVE_STALE_SECONDS = 2.0
	}
}
