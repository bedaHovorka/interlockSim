/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.sim.Train
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests for [DefaultTrainActuatorPort].
 *
 * @since Issue #545 (SP0.6 — Goal 10)
 */
@DisplayName("DefaultTrainActuatorPort — unit coverage")
class DefaultTrainActuatorPortTest {
	@Test
	@DisplayName("setTargetSpeed delegates to Train.setTargetSpeed")
	fun setTargetSpeedDelegatesToTrain() {
		val train = mockk<Train>(relaxed = true)
		val port = DefaultTrainActuatorPort(train)

		port.setTargetSpeed(20.0)

		verify(exactly = 1) { train.setTargetSpeed(20.0) }
	}

	@Test
	@DisplayName("setTargetSpeed zero is valid (full stop)")
	fun setTargetSpeedZeroIsValid() {
		val train = mockk<Train>(relaxed = true)
		val port = DefaultTrainActuatorPort(train)

		port.setTargetSpeed(0.0)

		verify(exactly = 1) { train.setTargetSpeed(0.0) }
	}

	@Test
	@DisplayName("setTargetSpeed negative throws IllegalArgumentException")
	fun setTargetSpeedNegativeThrows() {
		val train = mockk<Train>(relaxed = true)
		val port = DefaultTrainActuatorPort(train)

		assertFailsWith<IllegalArgumentException> {
			port.setTargetSpeed(-1.0)
		}
	}

	@Test
	@DisplayName("DefaultTrainActuatorPort is a TrainActuatorPort")
	fun implementsTrainActuatorPort() {
		val train = mockk<Train>(relaxed = true)
		val port: TrainActuatorPort = DefaultTrainActuatorPort(train)
		// Verify the interface contract via the type assignment; compile-time check only.
		assertThat(port).isEqualTo(port)
	}
}
