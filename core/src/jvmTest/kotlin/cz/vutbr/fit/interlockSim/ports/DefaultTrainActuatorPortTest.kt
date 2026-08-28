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

	// ── holdAtStation (SP2a.3, Issue #554) ───────────────────────────────────

	@Test
	@DisplayName("holdAtStation delegates to Train.holdAtStation")
	fun holdAtStationDelegatesToTrain() {
		val train = mockk<Train>(relaxed = true)
		val port = DefaultTrainActuatorPort(train)

		port.holdAtStation(45.0)

		verify(exactly = 1) { train.holdAtStation(45.0) }
	}

	@Test
	@DisplayName("holdAtStation with positive duration calls Train.holdAtStation")
	fun holdAtStationPositiveDurationIsValid() {
		val train = mockk<Train>(relaxed = true)
		val port = DefaultTrainActuatorPort(train)

		port.holdAtStation(30.0)

		verify(exactly = 1) { train.holdAtStation(30.0) }
	}

	@Test
	@DisplayName("holdAtStation zero duration throws IllegalArgumentException before reaching Train")
	fun holdAtStationZeroDurationThrows() {
		val train = mockk<Train>(relaxed = true)
		val port = DefaultTrainActuatorPort(train)

		assertFailsWith<IllegalArgumentException> {
			port.holdAtStation(0.0)
		}

		// Train.holdAtStation must NOT have been called
		verify(exactly = 0) { train.holdAtStation(any()) }
	}

	@Test
	@DisplayName("holdAtStation negative duration throws IllegalArgumentException before reaching Train")
	fun holdAtStationNegativeDurationThrows() {
		val train = mockk<Train>(relaxed = true)
		val port = DefaultTrainActuatorPort(train)

		assertFailsWith<IllegalArgumentException> {
			port.holdAtStation(-5.0)
		}

		verify(exactly = 0) { train.holdAtStation(any()) }
	}
}
