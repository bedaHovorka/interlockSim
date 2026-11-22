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

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.koin.core.component.get

/**
 * [PathReservationServiceTestBase] on the Praha station asset (`praha-hlavni-nadrazi-station.xml`), with
 * the separator names its reservation tests share (Issue #1385 review round). The station has eleven
 * InOuts, not the base's default two.
 */
abstract class PrahaPathReservationTestBase : PathReservationServiceTestBase() {
	override fun loadTestContext(): DefaultSimulationContext =
		CommonTestFixtures.parseSimulationContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML, get())

	override val expectedInOutCount: Int = PRAHA_IN_OUTS

	protected companion object {
		const val PRAHA_IN_OUTS = 11
		const val TRAIN = "Train #2"
		const val S2 = "S2"
		const val S5 = "S5"
		const val S9 = "S9"
		const val S11 = "S11"
		const val S14 = "S14"
		const val S15 = "S15"
		const val S_4_4 = "S_4_4"
		const val SW24 = "SW24"
		const val N_VYS_1 = "N-Vys-1"
	}
}
