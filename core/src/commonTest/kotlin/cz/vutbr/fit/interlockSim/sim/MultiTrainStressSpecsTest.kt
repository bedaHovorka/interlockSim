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
import assertk.assertions.isNotEmpty
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
 * `heavyTest`. It is cheap because it asks [cz.vutbr.fit.interlockSim.context.RouteFinder] only
 * about pairs that do have a route: those searches answer in milliseconds, while a search for a
 * pair without one enumerates every switch-constrained path first.
 */
class MultiTrainStressSpecsTest : CommonKoinTestBase() {
	@Test
	fun everyStressPairHasASwitchLegalRouteOnPraha() {
		val specs = MultiTrainStressSpecs.twentyTrainSpecs()
		assertThat(specs, name = "stress specs").hasSize(MultiTrainStressSpecs.TRAINS)

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
		for ((inName, outName) in specs.map { it.inName to it.outName }.distinct()) {
			val routes =
				routeFinder.findRoutes(
					inOutsByName.getValue(inName).staticRef,
					inOutsByName.getValue(outName).staticRef,
					ctx
				)
			assertThat(routes, name = "routes $inName -> $outName").isNotEmpty()
		}
	}
}
