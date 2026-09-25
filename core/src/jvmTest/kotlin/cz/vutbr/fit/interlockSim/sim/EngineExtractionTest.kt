/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1059 — Engine extracted from Train.Motor.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.Continuous
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.engineOf
import cz.vutbr.fit.interlockSim.testutil.motorOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins the Issue #1059 extraction contract: Train owns a top-level [Engine] process
 * (not an inner `Motor`), reachable as a [Continuous] via the test helper, with the
 * documented acceleration bounds on [Engine]'s companion.
 */
@DisplayName("Engine extraction from Train.Motor (Issue #1059)")
class EngineExtractionTest : KoinTestBase() {
	@Test
	@DisplayName("Train.engine is a top-level Engine Continuous process")
	fun trainEngineIsTopLevelEngineContinuous() {
		val train = Train(createMockSimulationContext(), createTimetable())
		val engine = engineOf(train)

		assertThat(engine).isInstanceOf(Engine::class)
		assertThat(engine).isInstanceOf(Continuous::class)
		assertThat(engine).isInstanceOf(Process::class)
		assertThat(engine::class.simpleName).isEqualTo("Engine")
		assertThat(engine::class.qualifiedName)
			.isEqualTo("cz.vutbr.fit.interlockSim.sim.Engine")
	}

	@Test
	@DisplayName("motorOf remains an alias for engineOf after the rename")
	fun motorOfAliasMatchesEngineOf() {
		val train = Train(createMockSimulationContext(), createTimetable())
		assertThat(motorOf(train)).isEqualTo(engineOf(train))
	}

	@Test
	@DisplayName("AccelerationStopTest keeps its deceleration flags and half-speed test")
	fun accelerationStopTestKeepsHistoricalMotorBehavior() {
		assertThat(AccelerationStopTest.ACCELERATION_ENDED.isDecelerate()).isEqualTo(false)
		assertThat(AccelerationStopTest.DECELERATION_ENDED.isDecelerate()).isEqualTo(true)
		assertThat(AccelerationStopTest.TO_HALF_SPEED.condition(20.0, 10.0)).isTrue()
	}

	private fun createTimetable(): Timetable {
		val origin = mockk<cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut>(relaxed = true)
		val destination = mockk<cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut>(relaxed = true)
		every { origin.name } returns "A"
		every { destination.name } returns "B"
		return Timetable(origin, destination, Time(0.0), Time(100.0), 10.0)
	}
}
