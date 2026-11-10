/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP1 (#1148) / demand 3 (#1179): guard on the Praha stress route pairs.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Fast guard for the 20-train Praha stress scenario (Issue #1179).
 *
 * The heavy `MultiTrainScaleStressTest` can only pass when every one of its specs asks for an
 * `(entry, exit)` pair that has a switch-legal route on `praha-hlavni-nadrazi.xml`. 13 of the
 * 20 pairs of the old rotation had none, so those trains were refused with `NO_ROUTE` on their
 * first attempt and never ran.
 *
 * This test runs in `commonTest` (JVM `test` and `:core:linuxX64Test`), so a spec change that
 * reintroduces an impossible pair fails in the normal gate instead of only in the manual
 * `heavyTest`. Route existence is checked with
 * [cz.vutbr.fit.interlockSim.context.RouteFinder.isRouteAvailable], which states the intent
 * directly instead of listing routes the guard never looks at.
 *
 * It is fast because the switch-constrained path enumeration of these Praha pairs is small
 * (measured 1-22 ms per pair, see `docs/goal9b-demands/SP1-fail-fast-harness.md`), not because
 * an existence check stops the search early: [cz.vutbr.fit.interlockSim.context.RouteFinder]
 * walks every switch-constrained path up to its depth cap whether the pair has a route or not,
 * and `maxRoutes` bounds only the returned list. A pair without a route answers with an empty
 * list after the same bounded enumeration, so this guard cannot hang on an impossible pair;
 * the gate fails cleanly instead.
 */
class MultiTrainStressSpecsTest : CommonKoinTestBase() {
	@Test
	fun everyStressPairHasASwitchLegalRouteOnPraha() {
		val specs = MultiTrainStressSpecs.twentyTrainSpecs()
		assertThat(specs, name = "stress specs").hasSize(MultiTrainStressSpecs.TRAINS)
		val usedPairs = specs.map { it.inName to it.outName }.distinct()
		assertThat(
			usedPairs,
			name = "distinct pairs used by the stress rotation"
		).hasSize(MultiTrainStressSpecs.LEGAL_PAIRS.size)

		val ctx =
			CommonTestFixtures
				.parseSimulationContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML, get())
				.tracked()
		val inOutsByName = ctx.getInOuts().associateBy { it.name }
		val usedNames = specs.flatMap { listOf(it.inName, it.outName) }.distinct()
		assertThat(
			usedNames.filterNot { inOutsByName.containsKey(it) },
			name = "stress InOut names missing from the Praha fixture"
		).isEmpty()

		val routeFinder = ctx.getRouteFinder()
		for ((inName, outName) in usedPairs) {
			assertThat(
				routeFinder.isRouteAvailable(
					inOutsByName.getValue(inName).staticRef,
					inOutsByName.getValue(outName).staticRef,
					ctx
				),
				name = "switch-legal route $inName -> $outName"
			).isTrue()
		}
	}
}
