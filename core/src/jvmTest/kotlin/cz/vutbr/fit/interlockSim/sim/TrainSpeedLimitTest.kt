/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Focused unit tests for Train.currentSpeedLimitMps (SP4.3 bugfix, Issue #565).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.domain.ABSOLUTE_MAX_SPEED
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.createMockNodeCell
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Focused unit tests pinning the [Train.currentSpeedLimitMps] invariant fixed in SP4.3
 * (Issue #565 regression, `Train.kt` ~line 1352).
 *
 * ## The invariant
 *
 * `currentSpeedLimitMps` is the **physical** track speed limit along the reserved path,
 * documented as "independent of the signal aspect". The SP4.3 bugfix changed its fold to
 * seed from [ABSOLUTE_MAX_SPEED] and skip `pathToSemaphore`'s first+last endpoints, rather
 * than delegating to `Path.maxSpeed(getFirst())` which seeded its running minimum from the
 * FIRST separator's own live `allowedSpeed()`.
 *
 * That first separator is the semaphore the train most recently departed — a bidirectional
 * semaphore that routinely reports `STOP` (`allowedSpeed() == 0.0`) for the direction it no
 * longer faces. A running minimum seeded at 0.0 can never recover, so the old code collapsed
 * this signal-independent value to 0.0 for the rest of the leg; a train at velocity 0 never
 * advances `pathToSemaphore`, latching it at a stop forever. The bug was latent since #552
 * and only surfaced once SP4.3 wired [AlgorithmicTrainDecisionPolicy] to call
 * `setTargetSpeed` every tick.
 *
 * These tests assert the fold returns the intermediate track limit (NOT 0.0) when the first
 * separator reports STOP — the exact discrimination between the fixed and the buggy code.
 *
 * ## Why reflection
 *
 * [Train.pathToSemaphore] is a `private var` populated only inside `Train`'s private inner
 * `Front` class during a live `context.run()` (in `accelerateToSignal`). There is no
 * `internal` seam, and the conservative `sim/` rule forbids refactoring the getter to
 * extract a testable function. Reflective injection of the private field is the
 * least-invasive way to exercise the getter against a controlled path — it touches no
 * production code. The path itself is built from real objects ([ArrayPath] + real
 * separators + [SimpleTrackBlock]) reusing the `PathMaxSpeedCalculationTest` pattern, so
 * the real `contributeToPathMaxSpeed` fold runs (no mocking of the fold logic).
 *
 * The first separator is a [cz.vutbr.fit.interlockSim.testutil.createMockNodeCell] with
 * `speed = 0.0`, standing in for the departed bidirectional STOP semaphore: the fixed fold
 * never reads `getFirst().allowedSpeed()`, so any 0.0-allowedSpeed first separator
 * discriminates the new (track-limit) result from the old (0.0) result.
 *
 * @since Issue #565 (SP4.3 bugfix — Goal 10 single reactive train end-to-end)
 */
@DisplayName("Train.currentSpeedLimitMps SP4.3 bugfix invariant (#565)")
class TrainSpeedLimitTest : KoinTestBase() {
	private lateinit var mockContext: MockSimulationContext
	private lateinit var mockInOut: DynamicInOut

	@BeforeEach
	fun setUp() {
		mockContext = createMockSimulationContext()
		// Trigger lazy initialization of dynamic wrappers (mirrors TrainPathInteractionTest).
		mockContext.getInOuts()
		mockInOut = mockk(relaxed = true)
		every { mockInOut.name } returns "ENTRY"
		every { mockInOut.toString() } returns "InOut:ENTRY"
	}

	// ── Reflective seam ───────────────────────────────────────────────────────

	/**
	 * Injects [path] into the private [Train.pathToSemaphore] field. Test-only; see class
	 * KDoc for why reflection is the chosen approach.
	 */
	private fun setPathToSemaphore(
		train: Train,
		path: Path
	) {
		val field = Train::class.java.getDeclaredField("pathToSemaphore")
		field.isAccessible = true
		field.set(train, path)
	}

	private fun newTrain(): Train {
		val mockOutOut = mockk<DynamicInOut>(relaxed = true)
		every { mockOutOut.name } returns "EXIT"
		every { mockOutOut.toString() } returns "InOut:EXIT"
		val timetable = Timetable(mockInOut, mockOutOut, Time(0.0), Time(0.0), 50.0)
		return Train(mockContext, timetable)
	}

	// ── Tests ─────────────────────────────────────────────────────────────────

	@Test
	fun `currentSpeedLimitMps is ABSOLUTE_MAX_SPEED when no path is reserved`() {
		val train = newTrain()

		// A freshly-constructed Train has pathToSemaphore == null — the `?: ABSOLUTE_MAX_SPEED`
		// branch.  This pins the null-path fallback, currently untested at the unit level.
		assertThat(train.currentSpeedLimitMps).isEqualTo(ABSOLUTE_MAX_SPEED)
	}

	@Test
	fun `currentSpeedLimitMps ignores departed STOP-seed and folds intermediate track limit`() {
		val train = newTrain()

		// First separator = the semaphore the train most recently departed, reporting STOP
		// (allowedSpeed 0.0) for the direction it no longer faces.
		val departedStop = createMockNodeCell(name = "DepartedStopSemaphore", speed = 0.0)
		// A mid-path separator (separator contribution is a no-op on the running minimum).
		val midSep = createMockNodeCell(name = "MidSeparator", speed = 80.0)
		// getLast() must be an OrientedPathSeparator — a real semaphore fills that role.
		val exitSemaphore = createDynamicInstance(RailSemaphore(false, Cell.SpatialType.HORIZONTAL))

		// One intermediate track with a 40 m/s limit in the departed-separator direction.
		val track = SimpleTrackBlock(departedStop, midSep, length = 100.0, maxSpeed1 = 40.0, maxSpeed2 = 40.0)

		val path = ArrayPath(mockContext)
		path.addLast(departedStop)
		path.addLast(track)
		path.addLast(midSep)
		path.addLast(exitSemaphore)

		setPathToSemaphore(train, path)

		// Fixed fold: seed ABSOLUTE_MAX_SPEED (90.0), skip first+last, fold the track → min(90.0, 40.0) = 40.0.
		// Buggy fold (Path.maxSpeed(getFirst())) would seed 0.0 → 0.0.  Asserting 40.0 (NOT 0.0) is the guard.
		assertThat(train.currentSpeedLimitMps).isEqualTo(40.0)
	}

	@Test
	fun `currentSpeedLimitMps is ABSOLUTE_MAX_SPEED when path has only endpoints`() {
		val train = newTrain()

		val first = createMockNodeCell(name = "FirstSeparator", speed = 0.0)
		val last = createDynamicInstance(RailSemaphore(false, Cell.SpatialType.HORIZONTAL))

		val path = ArrayPath(mockContext)
		path.addLast(first)
		path.addLast(last)

		setPathToSemaphore(train, path)

		// No intermediate elements → every element is skipped (first == getFirst, last == getLast)
		// and the seed ABSOLUTE_MAX_SPEED is returned.  Pins the < 3 element edge case.
		assertThat(train.currentSpeedLimitMps).isEqualTo(ABSOLUTE_MAX_SPEED)
	}
}
