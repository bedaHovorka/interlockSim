/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1376: the generator's pair selection with a redraw hook.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.koin.core.component.get
import kotlin.test.Test

/**
 * [selectPair], the deterministic scan behind [Generator.isPairAcceptable] (Issue #1376).
 *
 * With an accept-all hook it must pick exactly the pair the generator picked before the hook
 * existed: the first shuffled InOut and the first later one of the other side, or the first two
 * when all InOuts are on one side. A rejected pair moves on to the next partner of the same
 * origin, then to the next origin; it draws no random number. When no pair passes, it fails fast.
 *
 * The InOuts are the four of `cerveny_ujezd.xml`, two per side.
 */
class GeneratorPairSelectionTest : CommonKoinTestBase() {
	private companion object {
		const val WEST_ENTRY_1: String = "west_entry_1"
		const val WEST_ENTRY_2: String = "west_entry_2"
		const val EAST_EXIT_1: String = "east_exit_1"
		const val EAST_EXIT_2: String = "east_exit_2"

		val ACCEPT_ALL: (DynamicInOut, DynamicInOut) -> Boolean = { _, _ -> true }
	}

	private fun inOuts(): Map<String, DynamicInOut> {
		val ctx = CommonTestFixtures.parseSimulationContext(NetworkResources.CERVENY_UJEZD_XML, get()).tracked()
		return ctx.getInOuts().associateBy { it.name }
	}

	private fun Pair<DynamicInOut, DynamicInOut>.names(): Pair<String, String> = first.name to second.name

	/** The pre-#1376 rule of `Generator.generateRandomTimetable`, kept here as the reference. */
	private fun historicalPair(list: List<DynamicInOut>): Pair<DynamicInOut, DynamicInOut> =
		list[0] to (list.drop(1).firstOrNull { it.getOrientation() != list[0].getOrientation() } ?: list[1])

	private fun <T> permutations(items: List<T>): List<List<T>> =
		if (items.size <= 1) {
			listOf(items)
		} else {
			items.flatMap { head -> permutations(items - head).map { listOf(head) + it } }
		}

	@Test
	fun acceptAllPicksTheFirstInOutAndTheFirstLaterOneOfTheOtherSide() {
		val byName = inOuts()
		val shuffled = listOf(WEST_ENTRY_1, WEST_ENTRY_2, EAST_EXIT_2, EAST_EXIT_1).map(byName::getValue)

		assertThat(selectPair(shuffled, ACCEPT_ALL).names()).isEqualTo(WEST_ENTRY_1 to EAST_EXIT_2)
	}

	@Test
	fun acceptAllEqualsTheHistoricalRuleForEveryOrderOfTheFourInOuts() {
		val all = inOuts().values.toList()

		for (order in permutations(all)) {
			assertThat(selectPair(order, ACCEPT_ALL).names(), name = "order ${order.map { it.name }}")
				.isEqualTo(historicalPair(order).names())
		}
	}

	@Test
	fun acceptAllOnOneSideOnlyPicksTheFirstTwo() {
		val byName = inOuts()
		val westOnly = listOf(WEST_ENTRY_2, WEST_ENTRY_1).map(byName::getValue)

		assertThat(selectPair(westOnly, ACCEPT_ALL).names()).isEqualTo(WEST_ENTRY_2 to WEST_ENTRY_1)
		assertThat(selectPair(westOnly, ACCEPT_ALL).names()).isEqualTo(historicalPair(westOnly).names())
	}

	@Test
	fun aRejectedPartnerMovesOnToTheNextPartnerOfTheSameOrigin() {
		val byName = inOuts()
		val shuffled = listOf(WEST_ENTRY_1, EAST_EXIT_1, WEST_ENTRY_2, EAST_EXIT_2).map(byName::getValue)

		val pair = selectPair(shuffled) { from, to -> !(from.name == WEST_ENTRY_1 && to.name == EAST_EXIT_1) }

		assertThat(pair.names()).isEqualTo(WEST_ENTRY_1 to EAST_EXIT_2)
	}

	@Test
	fun anOriginWithoutAnAcceptedPartnerMovesOnToTheNextOrigin() {
		val byName = inOuts()
		val shuffled = listOf(WEST_ENTRY_1, EAST_EXIT_1, WEST_ENTRY_2, EAST_EXIT_2).map(byName::getValue)

		// The next origin is the second shuffled InOut (east_exit_1); its partners are the other side
		// in shuffled order, so west_entry_1 would come first, and is rejected too.
		val pair = selectPair(shuffled) { from, to -> from.name != WEST_ENTRY_1 && to.name != WEST_ENTRY_1 }

		assertThat(pair.names()).isEqualTo(EAST_EXIT_1 to WEST_ENTRY_2)
	}

	@Test
	fun oneSideOnlyPairsAnOriginOnlyWithLaterEntries() {
		val byName = inOuts()
		val westOnly = listOf(WEST_ENTRY_1, WEST_ENTRY_2).map(byName::getValue)

		// The only later entry of west_entry_1 is rejected; west_entry_2 has no later entry.
		assertFailure { selectPair(westOnly) { from, _ -> from.name != WEST_ENTRY_1 } }
			.isInstanceOf<IllegalStateException>()
	}

	@Test
	fun noAcceptedPairFailsFast() {
		val all = inOuts().values.toList()

		assertFailure { selectPair(all) { _, _ -> false } }
			.isInstanceOf<IllegalStateException>()
			.messageContains("No InOut pair of this network passes isPairAcceptable")
	}
}
