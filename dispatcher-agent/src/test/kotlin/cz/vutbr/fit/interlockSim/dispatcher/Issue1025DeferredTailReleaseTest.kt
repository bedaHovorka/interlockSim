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
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.StaleTailReclaimHarness
import cz.vutbr.fit.interlockSim.dispatcher.testutil.assertHealthyReclaim
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Issue #1025: an approach-locked deferral must postpone a tail release, never leak it.
 *
 * ## What this pins
 *
 * When a proceed aspect stands at the boundary between a stopped train's occupied head and its
 * un-travelled tail, [RegistryPartialRouteReleaser] drops the signal to STOP and **defers** the
 * physical release (a [TailRelease.deferred] outcome); [OrphanReservationSweeper] retries on its
 * next sweep. The deferral is the #1025 fix itself, so two things must hold in a live run:
 *
 * 1. it actually happens — the boundary between a stopped train and its granted route is exactly
 *    where the #1025 FATAL lived, so a live reclaim there must meet a standing proceed;
 * 2. it does not strand the tail — after a deferral the sweeper still reclaims part of that same
 *    train's deferred tail: either the retry lands with the signal now at STOP, or the committed
 *    train books the first tail block and the shrunken tail is re-offered one threshold later (a
 *    changed tail restarts the clock). A release for a different train, or for blocks outside the
 *    deferred offer, does not count — the stranded-tail leak would survive both.
 *
 * The deferral is **observed**, not forced: no stub decides the outcome. The harness runs the
 * sibling [Issue1025StaleTailReleaseTest] scenario (same 300 s end time, same aggressive 2 s
 * threshold — the rationale for both is that test's KDoc) with its `partialReleaser` hook wrapping
 * the production [RegistryPartialRouteReleaser] in a recording decorator. Every call still runs
 * against the live registry and track, exactly as `ExampleRegistry.wireDispatcherAgent` wires it;
 * the test only records each call — the train and block ids it was offered, and what came back.
 */
@DisplayName("Issue #1025 — a tail deferred by approach locking is released afterwards, not stranded")
@Tag("integration-test")
class Issue1025DeferredTailReleaseTest : DispatcherKoinTestBase() {
	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	fun `a deferred tail release is followed by a later reclaim and the run stays healthy`() {
		val context = TestFixtures.newShuntingSimulationContext().tracked()
		val registry = context.scope.get<PathReservationRegistry>()
		val calls = mutableListOf<RecordedTailRelease>()
		val production =
			RegistryPartialRouteReleaser(
				registry = registry,
				pathReservationService = context.getRoutingServices().getPathReservationService()
			)
		val recording =
			PartialRouteReleaser { trainId, blockIds ->
				production.releaseUntravelledTail(trainId, blockIds).also {
					calls += RecordedTailRelease(trainId, blockIds, it)
				}
			}

		val outcome =
			StaleTailReclaimHarness.run(
				context = context,
				simEndTime = Issue1025StaleTailReleaseTest.SIM_END_TIME,
				staleAfterSimSeconds = Issue1025StaleTailReleaseTest.AGGRESSIVE_STALE_SECONDS,
				partialReleaser = recording
			)

		// The survival check runs first: a FATAL inside a train process does not propagate out of
		// run(), the process dies and the run goes on, so a failure of the deferral assertions
		// must not mask (or be masked by) a dead train.
		outcome.assertHealthyReclaim()

		val firstDeferral = calls.indexOfFirst { it.outcome.deferred }
		assertThat(
			firstDeferral,
			name = "index of the first offer deferred by approach locking, or -1 if none was"
		).isGreaterThanOrEqualTo(0)
		val deferred = calls[firstDeferral]
		assertThat(
			calls.drop(firstDeferral + 1).any { later ->
				later.trainId == deferred.trainId &&
					later.outcome.released.any { it in deferred.offeredBlockIds }
			},
			name = "a later sweep reclaimed part of the same train's deferred tail"
		).isTrue()
	}

	/** One [PartialRouteReleaser.releaseUntravelledTail] call: what it was offered, what came back. */
	private data class RecordedTailRelease(
		val trainId: String,
		val offeredBlockIds: List<String>,
		val outcome: TailRelease
	)
}
