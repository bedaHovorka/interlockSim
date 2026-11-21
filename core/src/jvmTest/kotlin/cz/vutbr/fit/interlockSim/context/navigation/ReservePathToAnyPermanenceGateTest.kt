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
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Issue #1401: [DefaultPathReservationService.reservePathToAny] ends through the same permanence gate as
 * [DefaultPathReservationService.reservePathToAnyNextSemaphore]
 * ([DefaultPathReservationService.classifyExhaustedSemaphores]): a permanent verdict
 * ([ReservationResult.GeometricallyImpossible], [ReservationResult.DivergesFromHeldRoute],
 * [ReservationResult.NoPathExists]) stands only when every target was refused permanently; a set
 * with at least one merely blocked target ends as [ReservationResult.AllPathsBlocked].
 *
 * The per-target verdicts are forced: a spy of the real service on vyhybna.xml answers the internal
 * per-target `reservePath` overload from a script, so the attempt loop and its target discovery run
 * for real while each target's verdict is fixed. From `zA` the loop tries more than two targets (both
 * InOuts, then the semaphores), so "first target blocked, every later one permanent" ends on a
 * permanent last result — the case the gate turns into contention.
 */
class ReservePathToAnyPermanenceGateTest : PathReservationServiceTestBase() {
	private lateinit var zA: DynamicRailSemaphore
	private lateinit var spy: DefaultPathReservationService

	@BeforeEach
	fun setUpSpy() {
		zA = findSemaphoreByName("zA")
		spy = spyk(service as DefaultPathReservationService)
	}

	/** Answers the per-target `reservePath` calls of [TRAIN] in order; the last answer repeats. */
	private fun scriptTargets(vararg verdicts: ReservationResult) {
		every {
			spy.reservePath(TRAIN, any<DynamicPathSeparator>(), any<DynamicPathSeparator>(), any<Int>(), any())
		} returnsMany verdicts.toList()
	}

	private fun targetCount(): Int {
		val targets = mutableListOf<DynamicPathSeparator>()
		every {
			spy.reservePath(TRAIN, any<DynamicPathSeparator>(), any<DynamicPathSeparator>(), any<Int>(), any())
		} answers {
			targets += thirdArg<DynamicPathSeparator>()
			BLOCKED
		}
		spy.reservePathToAny(TRAIN, zA)
		return targets.size
	}

	@Test
	fun `zA tries more than two targets, so a blocked first target is followed by later ones`() {
		assertThat(targetCount(), name = "targets reservePathToAny tries from zA").isGreaterThan(2)
	}

	@Test
	fun `a blocked target followed by geometrically impossible ones ends as AllPathsBlocked`() {
		val attempts = targetCount()
		scriptTargets(BLOCKED, GEOMETRIC)

		val result = spy.reservePathToAny(TRAIN, zA)

		assertThat(result, name = "one target can clear, so the set is contention")
			.isInstanceOf<ReservationResult.AllPathsBlocked>()
		val blocked = result as ReservationResult.AllPathsBlocked
		assertThat(blocked.attemptedPaths, name = "the gate reports the target count").isEqualTo(attempts)
		assertThat(blocked.blockedBy, name = "the gate carries no per-candidate list").isEmpty()
	}

	@Test
	fun `a blocked target followed by NoPathExists ends as AllPathsBlocked`() {
		scriptTargets(BLOCKED, ReservationResult.NoPathExists)

		assertThat(spy.reservePathToAny(TRAIN, zA)).isInstanceOf<ReservationResult.AllPathsBlocked>()
	}

	@Test
	fun `a blocked target followed by divergent ones ends as AllPathsBlocked`() {
		scriptTargets(BLOCKED, DIVERGENT)

		assertThat(spy.reservePathToAny(TRAIN, zA)).isInstanceOf<ReservationResult.AllPathsBlocked>()
	}

	@Test
	fun `a blocked target between geometrically impossible ones ends as AllPathsBlocked`() {
		val attempts = targetCount()
		scriptTargets(GEOMETRIC, BLOCKED, GEOMETRIC)

		val result = spy.reservePathToAny(TRAIN, zA)

		assertThat(result, name = "the middle target can clear, so the set is contention")
			.isInstanceOf<ReservationResult.AllPathsBlocked>()
		val blocked = result as ReservationResult.AllPathsBlocked
		assertThat(blocked.attemptedPaths, name = "the gate reports the target count").isEqualTo(attempts)
		assertThat(blocked.blockedBy, name = "the gate carries no per-candidate list").isEmpty()
	}

	@Test
	fun `a Success after a permanent target is returned as it is`() {
		val success = ReservationResult.Success(listOf(blockBetween("B", "zB")))
		scriptTargets(GEOMETRIC, success)

		assertThat(spy.reservePathToAny(TRAIN, zA)).isSameInstanceAs(success)
	}

	@Test
	fun `a permanent target followed by a blocked last target keeps the last target's result`() {
		scriptTargets(GEOMETRIC, BLOCKED)

		assertThat(spy.reservePathToAny(TRAIN, zA), name = "the last target's own AllPathsBlocked")
			.isSameInstanceAs(BLOCKED)
	}

	@Test
	fun `every target geometrically impossible keeps GeometricallyImpossible`() {
		scriptTargets(GEOMETRIC)

		assertThat(spy.reservePathToAny(TRAIN, zA)).isSameInstanceAs(GEOMETRIC)
	}

	@Test
	fun `every target without a route keeps NoPathExists`() {
		scriptTargets(ReservationResult.NoPathExists)

		assertThat(spy.reservePathToAny(TRAIN, zA)).isEqualTo(ReservationResult.NoPathExists)
	}

	@Test
	fun `every target divergent keeps DivergesFromHeldRoute`() {
		scriptTargets(DIVERGENT)

		assertThat(spy.reservePathToAny(TRAIN, zA)).isSameInstanceAs(DIVERGENT)
	}

	@Test
	fun `a mixed all-permanent set keeps the last target's permanent class`() {
		scriptTargets(GEOMETRIC, DIVERGENT, ReservationResult.NoPathExists)

		assertThat(spy.reservePathToAny(TRAIN, zA)).isEqualTo(ReservationResult.NoPathExists)
	}

	@Test
	fun `a Conflict after a blocked target still aborts the loop at once`() {
		val conflict = ReservationResult.Conflict(blockBetween("B", "zB"), "other")
		scriptTargets(BLOCKED, conflict)

		assertThat(spy.reservePathToAny(TRAIN, zA)).isSameInstanceAs(conflict)
		verify(exactly = 2) {
			spy.reservePath(TRAIN, any<DynamicPathSeparator>(), any<DynamicPathSeparator>(), any<Int>(), any())
		}
	}

	@Test
	fun `a NonContiguousStart after a blocked target still aborts the loop at once`() {
		val nonContiguous = ReservationResult.NonContiguousStart("zA", "unit-test: start far from the train")
		scriptTargets(BLOCKED, nonContiguous)

		assertThat(spy.reservePathToAny(TRAIN, zA)).isSameInstanceAs(nonContiguous)
		verify(exactly = 2) {
			spy.reservePath(TRAIN, any<DynamicPathSeparator>(), any<DynamicPathSeparator>(), any<Int>(), any())
		}
	}

	private companion object {
		const val TRAIN = "train1"
		val BLOCKED = ReservationResult.AllPathsBlocked(attemptedPaths = 1)
		val GEOMETRIC = ReservationResult.GeometricallyImpossible("unit-test: geometric target")
		val DIVERGENT = ReservationResult.DivergesFromHeldRoute("doB1", "unit-test: divergent target")
	}
}
