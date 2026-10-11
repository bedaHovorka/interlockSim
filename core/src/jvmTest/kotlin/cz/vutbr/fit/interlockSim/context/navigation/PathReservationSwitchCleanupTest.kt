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
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Switch cleanup on the production release entry points (Issue #1165 split). */
@Tag("integration-test")
class PathReservationSwitchCleanupTest : PathReservationServiceTestBase() {
	@Test
	fun `unregister unlocks all switches and clears switch registry`() {
		// Arrange: reserve a path through a switch (vyhybna.xml)
		val result = service.reservePath("train1", inOut1, inOut2)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val switches = registry.getSwitches("train1")
		assertThat(switches).isNotEmpty()
		switches.forEach { switch ->
			assertThat(switch.locked).isTrue()
		}

		// Act: production cleanup path
		val releasedBlocks = service.unregister("train1")

		// Assert: blocks and switches released
		assertThat(releasedBlocks).isNotEmpty()
		assertThat(registry.getBlocks("train1")).isEmpty()
		assertThat(registry.getSwitches("train1")).isEmpty()
		switches.forEach { switch ->
			assertThat(switch.locked).isFalse()
		}
	}

	@Test
	fun `releaseTrainReservations unlocks switches through production entry point`() {
		val result = service.reservePath("train1", inOut1, inOut2)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val switches = registry.getSwitches("train1")
		assertThat(switches).isNotEmpty()
		switches.forEach { switch ->
			assertThat(switch.locked).isTrue()
		}

		simulationContext.releaseTrainReservations("train1")

		assertThat(registry.getBlocks("train1")).isEmpty()
		assertThat(registry.getSwitches("train1")).isEmpty()
		switches.forEach { switch ->
			assertThat(switch.locked).isFalse()
		}
	}
}
