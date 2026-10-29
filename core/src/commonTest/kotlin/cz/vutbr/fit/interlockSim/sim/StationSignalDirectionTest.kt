/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Signal direction in the [Station] classification (Issue #1192, SP2 demand D4).
 *
 * A platform track needs a departure signal at each end (the two end signals face away from each
 * other); a home signal must face the train entering from its InOut. Signal-bounded blocks whose
 * signals face the same way (an automatic block section, a deep throat) are not station tracks.
 */
class StationSignalDirectionTest : CommonKoinTestBase() {
	private fun station(xml: String): Station {
		val ctx: DefaultSimulationContext = CommonTestFixtures.parseSimulationContext(xml, get()).tracked()
		ctx.getInOuts() // initialize dynamic wrappers
		return Station.from(ctx)
	}

	@Test
	fun automaticBlockSectionAndDeepThroatAreNotStationTracks() {
		val station = station(NetworkResources.STATION_THROAT_AND_AUTOMATIC_BLOCK_XML)

		assertThat(station.platformTracks.map { it.id }).containsExactly("(16,8)-(25,8)", "(17,9)-(24,9)")
		assertThat(station.topologyCapacity).isEqualTo(2)
		assertThat(station.capacity()).isEqualTo(2)
	}

	@Test
	fun theLongerOpenLineAndThroatStillHaveTheirHomeSignals() {
		val station = station(NetworkResources.STATION_THROAT_AND_AUTOMATIC_BLOCK_XML)

		assertThat(station.entryExitBlocks.map { it.name to it.signals.map { s -> s.name } })
			.containsExactly("kA" to listOf("o1"), "kB" to listOf("hB"))
	}

	@Test
	fun signalNextToAnInOutFacingAwayFromTheIncomingTrainIsNoHomeSignal() {
		val station = station(NetworkResources.STATION_HOME_SIGNAL_FACING_AWAY_XML)

		val byName = station.entryExitBlocks.associateBy { it.name }
		assertThat(byName.getValue("kA").signals).isEmpty()
		assertThat(byName.getValue("kB").signals.map { it.name }).containsExactly("zB")
	}

	@Test
	fun prahaSameDirectionBlockStaysOutsideTheStationTracks() {
		val station = station(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML)

		assertThat(station.platformTracks.map { it.id }).doesNotContain("(4,20)-(8,20)")
		assertThat(station.topologyCapacity).isEqualTo(9)
	}
}
