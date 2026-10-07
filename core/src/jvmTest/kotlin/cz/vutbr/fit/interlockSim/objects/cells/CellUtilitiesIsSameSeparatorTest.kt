/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.cells

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.cellsOfType
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Tests for [CellUtilities.isSameSeparator], the separator identity check shared by
 * `DefaultTopologyNavigator` and `DefaultAutomaticPathFindingService` (Issue #1123).
 *
 * Every separator type of `vyhybna.xml` (InOut, semaphore, switch) is checked in its static form and
 * in its dynamic wrapper, in both argument orders.
 */
class CellUtilitiesIsSameSeparatorTest : KoinTestBase() {
	private val simulationContextFactory: SimulationContextFactory by inject()
	private val editingContextFactory: JvmEditingContextFactory by inject()

	/** (dynamic wrapper, its static cell) for every InOut, semaphore and switch of `vyhybna.xml`. */
	private fun wrapperPairs(): List<Pair<PathSeparator, PathSeparator>> {
		val context =
			TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory).tracked()
		val grid = context.getRailWayNetGrid()
		val pairs =
			context.getInOuts().map { it to it.staticRef } +
				grid.cellsOfType<DynamicRailSemaphore>().map { it to it.staticRef } +
				grid.cellsOfType<DynamicRailSwitch>().map { it to it.staticRef }
		// More than one pair, so the distinctness double loop below stays meaningful.
		assertThat(pairs.size).isGreaterThan(1)
		return pairs
	}

	@Test
	fun `a separator matches itself and its counterpart in every static and dynamic combination`() {
		for ((dynamic, static) in wrapperPairs()) {
			assertThat(CellUtilities.isSameSeparator(dynamic, dynamic)).isTrue()
			assertThat(CellUtilities.isSameSeparator(static, static)).isTrue()
			assertThat(CellUtilities.isSameSeparator(dynamic, static)).isTrue()
			assertThat(CellUtilities.isSameSeparator(static, dynamic)).isTrue()
		}
	}

	@Test
	fun `distinct network elements never match, whatever their form`() {
		val pairs = wrapperPairs()
		for ((i, first) in pairs.withIndex()) {
			for ((j, second) in pairs.withIndex()) {
				if (i == j) continue
				assertThat(CellUtilities.isSameSeparator(first.first, second.first)).isFalse()
				assertThat(CellUtilities.isSameSeparator(first.first, second.second)).isFalse()
				assertThat(CellUtilities.isSameSeparator(first.second, second.first)).isFalse()
				assertThat(CellUtilities.isSameSeparator(first.second, second.second)).isFalse()
			}
		}
	}

	@Test
	fun `a separator that is neither a node cell nor a dynamic wrapper is rejected`() {
		val foreign = mockk<PathSeparator>()
		val node = wrapperPairs().first().second

		assertFailure { CellUtilities.isSameSeparator(foreign, node) }
			.isInstanceOf(IllegalStateException::class)
	}
}
