/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotInstanceOf
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Issue #904: `reservePath`'s Step 2i `registerPathInfo` call must release exactly what
 * *this* call acquired (blocks, switches, cleared signal) when the registry's merge
 * aborts -- transactionally complete, matching the #901 standard applied everywhere else
 * in `reservePath`. Before this fix the abort left an orphaned RESERVED tail relying on
 * `OrphanReservationSweeper` to reclaim it later (never, in `:fast-sim`/bare-`:core`).
 *
 * The merge abort is reached by directly corrupting the registry's stored `PathInfo`
 * target (the same technique `MergeAbortSimSurvivalTest`, dispatcher-agent, uses) after a
 * REAL reservation has established the train's physical footprint -- so the subsequent
 * `reservePath` call is a legitimate, contiguous request that genuinely reserves new
 * resources before Step 2i's merge rejects the corrupted target.
 */
@Tag("integration-test")
class PathReservationMergeAbortResourceReleaseTest : PathReservationServiceTestBase() {
	@Test
	fun `reservePath releases blocks switches and signal when the Step 2i merge aborts`() {
		val zA = findSemaphoreByName("zA")
		val doA1 = findSemaphoreByName("doA1")
		val zB = findSemaphoreByName("zB")
		val trainId = "t904_step2i"

		// Seed a PathInfo with NO real reservation behind it (this is the FIRST-ever
		// registerPathInfo call for this trainId, so it stores directly -- no merge, no
		// blocks/switches/signal acquired). This gives the train a PathInfo whose target
		// (zB, unrelated to the real reservation below) will not match a genuinely reserved
		// candidate's start -- mirroring the "probe train, no footprint" technique
		// MergeAbortSimSurvivalTest (dispatcher-agent) already uses for the same reason:
		// registerPathInfo is a pure data-structure operation (I1) uncoupled from
		// trainToBlocks, so this cannot corrupt anything real.
		val seedPath = ArrayPath(simulationContext)
		seedPath.add(zB)
		registry.registerPathInfo(
			trainId,
			PathInfo(start = zB, target = zB, reservedPath = seedPath, entryDirections = emptyMap())
		)

		// Act: a REAL reservation, using the same zA -> doA1 pair the "golden equivalence"
		// case in MergeAbortNeverThrowsTest proves is a plain, single-candidate, no-G4,
		// no-switch-surprise route. The train's footprint is empty (no blocks/switches ever
		// registered for it), so Step 0's contiguity check passes vacuously for any start
		// (Issue #893's documented exemption) -- this genuinely reserves blocks, locks vA,
		// and clears zA's signal (Steps 2d-2h) before Step 2i's merge sees new.start (zA)
		// != old.target (zB, from the seed) and aborts.
		// maxDepth=3 restricts topological search to the single direct zA-doA1 route, the
		// same technique PathReservationStartDirectionTest uses to exclude vyhybna's longer sibling-branch
		// alternate -- otherwise an unrelated unconfigurable switch on that alternate
		// candidate would override this attempt's classification (Issue #903 first-hit-wins)
		// and mask what THIS test verifies. Confirmed single-candidate: the result below is
		// AllPathsBlocked(attemptedPaths=1), not GeometricallyImpossible.
		val extension = service.reservePath(trainId, zA, doA1, maxDepth = 3)

		// Assert: the extension's own newly-acquired blocks/switches must not leak -- there
		// was nothing before this attempt, so there must be nothing after it either.
		assertThat(registry.getBlocks(trainId))
			.withMessage("a merge-abort must release exactly the blocks THIS attempt acquired")
			.isEmpty()
		assertThat(registry.getSwitches(trainId))
			.withMessage("a merge-abort must release any switches THIS attempt locked")
			.isEmpty()

		// Assert: zA's signal must not be left showing a proceed aspect for a route the
		// registry no longer considers part of the train's path (G1 hazard).
		assertThat(zA.signal)
			.withMessage("a merge-abort must not leave a proceed aspect standing over an abandoned candidate")
			.isEqualTo(Signal.STOP)

		// The extension itself is not usable -- it's not what the caller asked for, and the
		// train's original (seeded) PathInfo must survive unchanged.
		assertThat(extension)
			.withMessage("a merge-abort must not be reported as a successful reservation")
			.isNotInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(registry.getPathInfo(trainId)!!.target)
			.withMessage("a merge-abort must not touch the stored PathInfo")
			.isEqualTo(zB)
	}
}
