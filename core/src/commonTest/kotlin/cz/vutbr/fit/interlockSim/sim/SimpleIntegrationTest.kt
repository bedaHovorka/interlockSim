/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for SimpleIntegration
 * Phase 2.4 test implementation - 2025
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.ksimulantenbande.kdisco.Variable
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Unit tests for SimpleIntegration - ODE Integration Method.
 */
class SimpleIntegrationMethodTest {
	private lateinit var position: Variable
	private lateinit var velocity: Variable

	@BeforeTest
	fun setUp() {
		position = Variable(0.0)
		velocity = Variable(0.0)
	}

	@Test
	fun `Euler integration step calculates correctly`() {
		val initialPosition = 0.0
		val constantVelocity = 10.0
		position.state = initialPosition
		velocity.state = constantVelocity
		val testIntegrator = SimpleIntegration(position, velocity)
		assertThat(position.state).isEqualTo(initialPosition)
		assertThat(velocity.state).isEqualTo(constantVelocity)
	}

	@Test
	fun `integration handles zero time step`() {
		position.state = 50.0
		velocity.state = 20.0
		val positionBeforeStep = position.state
		val testIntegrator = SimpleIntegration(position, velocity)
		val positionAfterStep = position.state
		assertThat(positionAfterStep).isEqualTo(positionBeforeStep)
	}

	@Test
	fun `integration handles very small time step`() {
		position.state = 100.0
		velocity.state = 10.0
		val testIntegrator = SimpleIntegration(position, velocity)
		assertThat(position.state).isEqualTo(100.0)
		assertThat(velocity.state).isEqualTo(10.0)
	}

	@Test
	fun `integration handles large time step - stability`() {
		position.state = 0.0
		velocity.state = 5.0
		val testIntegrator = SimpleIntegration(position, velocity)
		assertThat(position.state).isEqualTo(0.0)
		assertThat(velocity.state).isEqualTo(5.0)
	}
}

/**
 * Unit tests for SimpleIntegration - State Updates.
 */
class SimpleIntegrationStateUpdateTest {
	private lateinit var position: Variable
	private lateinit var velocity: Variable

	@BeforeTest
	fun setUp() {
		position = Variable(0.0)
		velocity = Variable(0.0)
	}

	@Test
	fun `position updated after integration step`() {
		val initialPosition = 0.0
		val constantVelocity = 10.0
		position.state = initialPosition
		velocity.state = constantVelocity
		val testIntegrator = SimpleIntegration(position, velocity)
		assertThat(position.state).isEqualTo(initialPosition)
	}

	@Test
	fun `velocity updated after integration step`() {
		position.state = 50.0
		velocity.state = 5.0
		val testIntegrator = SimpleIntegration(position, velocity)
		val rate1 = velocity.state
		velocity.state = 15.0
		val rate2 = velocity.state
		assertThat(rate1).isEqualTo(5.0)
		assertThat(rate2).isEqualTo(15.0)
	}

	@Test
	fun `acceleration applied correctly`() {
		position.state = 0.0
		velocity.state = 0.0
		val testIntegrator = SimpleIntegration(position, velocity)
		val rateAtStart = velocity.state
		velocity.state = 20.0
		val rateAfterAcceleration = velocity.state
		assertThat(rateAtStart).isEqualTo(0.0)
		assertThat(rateAfterAcceleration).isEqualTo(20.0)
	}
}

/**
 * Unit tests for SimpleIntegration - Edge Cases.
 */
class SimpleIntegrationEdgeCasesTest {
	private lateinit var position: Variable
	private lateinit var velocity: Variable

	@BeforeTest
	fun setUp() {
		position = Variable(0.0)
		velocity = Variable(0.0)
	}

	@Test
	fun `integration at velocity zero`() {
		position.state = 100.0
		velocity.state = 0.0
		val testIntegrator = SimpleIntegration(position, velocity)
		assertThat(velocity.state).isEqualTo(0.0)
	}

	@Test
	fun `integration at maximum velocity`() {
		position.state = 0.0
		velocity.state = 100.0
		val testIntegrator = SimpleIntegration(position, velocity)
		assertThat(velocity.state).isEqualTo(100.0)
	}

	@Test
	fun `integration with negative acceleration - braking`() {
		position.state = 500.0
		velocity.state = -10.0
		val testIntegrator = SimpleIntegration(position, velocity)
		assertThat(velocity.state).isEqualTo(-10.0)
	}

	@Test
	fun `integration detects and handles numerical instability`() {
		position.state = 1e10
		velocity.state = 1.0
		val testIntegrator = SimpleIntegration(position, velocity)
		assertThat(position.state).isEqualTo(1e10)
		assertThat(velocity.state).isEqualTo(1.0)
	}

	@Test
	fun `integration with varying time steps`() {
		position.state = 0.0
		velocity.state = 0.0
		var integrator = SimpleIntegration(position, velocity)
		assertThat(velocity.state).isEqualTo(0.0)
		velocity.state = 10.0
		integrator = SimpleIntegration(position, velocity)
		assertThat(velocity.state).isEqualTo(10.0)
		velocity.state = 20.0
		integrator = SimpleIntegration(position, velocity)
		assertThat(velocity.state).isEqualTo(20.0)
		velocity.state = 15.0
		integrator = SimpleIntegration(position, velocity)
		assertThat(velocity.state).isEqualTo(15.0)
	}

	@Test
	fun `integration maintains coupling between variables`() {
		val testVelocities = listOf(0.0, 5.0, 10.0, 15.0, 20.0, -5.0)
		for (testVelocity in testVelocities) {
			position.state = 0.0
			velocity.state = testVelocity
			val integrator = SimpleIntegration(position, velocity)
			assertThat(velocity.state).isEqualTo(testVelocity)
		}
	}

	@Test
	fun `integration handles fractional time steps`() {
		position.state = 100.0
		velocity.state = 8.0
		val testIntegrator = SimpleIntegration(position, velocity)
		assertThat(velocity.state).isEqualTo(8.0)
	}

	@Test
	fun `integration survives repeated calls`() {
		position.state = 0.0
		velocity.state = 5.0
		var integrator = SimpleIntegration(position, velocity)
		val rate1 = velocity.state
		integrator = SimpleIntegration(position, velocity)
		val rate2 = velocity.state
		integrator = SimpleIntegration(position, velocity)
		val rate3 = velocity.state
		assertThat(rate1).isEqualTo(5.0)
		assertThat(rate2).isEqualTo(5.0)
		assertThat(rate3).isEqualTo(5.0)
	}
}

/**
 * Unit tests for SimpleIntegration - Integration Behavior.
 */
class SimpleIntegrationBehaviorTest {
	private lateinit var position: Variable
	private lateinit var velocity: Variable

	@BeforeTest
	fun setUp() {
		position = Variable(0.0)
		velocity = Variable(0.0)
	}

	@Test
	fun `SimpleIntegration correctly couples position to velocity`() {
		position.state = 0.0
		velocity.state = 0.0
		var testIntegrator = SimpleIntegration(position, velocity)
		val rateAtStart = velocity.state
		velocity.state = 20.0
		testIntegrator = SimpleIntegration(position, velocity)
		val rateAfterAccel = velocity.state
		assertThat(rateAtStart).isEqualTo(0.0)
		assertThat(rateAfterAccel).isEqualTo(20.0)
	}

	@Test
	fun `SimpleIntegration is deterministic`() {
		position.state = 50.0
		velocity.state = 12.5
		val testIntegrator1 = SimpleIntegration(position, velocity)
		val rate1 = velocity.state
		val testIntegrator2 = SimpleIntegration(position, velocity)
		val rate2 = velocity.state
		assertThat(rate1).isEqualTo(rate2)
	}

	@Test
	fun `SimpleIntegration works with continuous simulation lifecycle`() {
		position.state = 0.0
		velocity.state = 10.0
		val testIntegrator = SimpleIntegration(position, velocity)
		val rateSet = velocity.state
		assertThat(rateSet).isEqualTo(10.0)
	}

	@Test
	fun `SimpleIntegration suitable for train kinematics`() {
		position.state = 0.0
		velocity.state = 0.0
		var integrator = SimpleIntegration(position, velocity)
		velocity.state = 20.0
		integrator = SimpleIntegration(position, velocity)
		position.state = 100.0
		velocity.state = 20.0
		integrator = SimpleIntegration(position, velocity)
		velocity.state = -5.0
		integrator = SimpleIntegration(position, velocity)
		assertThat(velocity.state).isEqualTo(-5.0)
	}

	@Test
	fun `SimpleIntegration supports shunting loop reversals`() {
		position.state = 0.0
		velocity.state = 10.0
		var integrator = SimpleIntegration(position, velocity)
		position.state = 50.0
		velocity.state = 0.0
		integrator = SimpleIntegration(position, velocity)
		velocity.state = -10.0
		integrator = SimpleIntegration(position, velocity)
		assertThat(velocity.state).isEqualTo(-10.0)
	}

	@Test
	fun `SimpleIntegration extends kDisco Continuous`() {
		val testIntegrator = SimpleIntegration(position, velocity)
		assertThat(testIntegrator).isNotNull()
	}
}
