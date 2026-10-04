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
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isSameInstanceAs
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
import kotlin.reflect.KVisibility

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
	fun accelerationStopTestKeepsHistoricalEngineBehavior() {
		assertThat(AccelerationStopTest.ACCELERATION_ENDED.isDecelerate()).isEqualTo(false)
		assertThat(AccelerationStopTest.DECELERATION_ENDED.isDecelerate()).isEqualTo(true)
		assertThat(AccelerationStopTest.TO_HALF_SPEED.margin(20.0, 10.0) <= 0.0).isTrue()
	}

	/**
	 * Issue #760: the crossing waits end on [AccelerationStopTest.margin] `<= 0`, so it must agree
	 * with the predicate each stop test's step poll tested before the conversion ([pollPredicate])
	 * everywhere — including at equality, where the inclusive comparison holds and the margin is
	 * exactly zero.
	 */
	@Test
	@DisplayName("AccelerationStopTest.margin is non-positive exactly when the old poll predicate holds")
	fun marginMirrorsCondition() {
		val speeds = listOf(0.0, 1e-9, 5.0, 9.999999, 10.0, 10.000001, 20.0, 40.0)
		for (test in AccelerationStopTest.entries) {
			for (target in speeds) {
				for (velocity in speeds) {
					assertThat(
						test.margin(target, velocity) <= 0.0,
						name = "$test margin <= 0 for target $target, velocity $velocity"
					).isEqualTo(pollPredicate(test, target, velocity))
				}
			}
		}
	}

	/** The pre-#760 `AccelerationStopTest.condition`, kept here as the reference the margin mirrors. */
	private fun pollPredicate(
		test: AccelerationStopTest,
		targetSpeed: Double,
		velocity: Double
	): Boolean =
		when (test) {
			AccelerationStopTest.ACCELERATION_ENDED -> targetSpeed <= velocity
			AccelerationStopTest.DECELERATION_ENDED -> targetSpeed >= velocity
			AccelerationStopTest.TO_HALF_SPEED -> targetSpeed <= 2 * velocity
		}

	/**
	 * Locks the adapter design commit `5b6985b7` chose: [Train] does not implement [Engine.Host]
	 * directly. [Engine.Host] is `internal`, so a public [Train] implementing it would have to
	 * publish the kinematic [cz.ksimulantenbande.kdisco.Variable]s on its public API — see the
	 * private `EngineHost` adapter in [Train].
	 */
	@Test
	@DisplayName("Train does not implement Engine.Host directly")
	fun trainDoesNotImplementEngineHostDirectly() {
		assertThat(Engine.Host::class.java.isAssignableFrom(Train::class.java)).isFalse()
	}

	/**
	 * [Engine.Host] stays `internal` so it can expose [cz.ksimulantenbande.kdisco.Variable]
	 * fields without those fields becoming part of any public API.
	 */
	@Test
	@DisplayName("Engine.Host is internal, not public")
	fun engineHostIsInternal() {
		assertThat(Engine.Host::class.visibility).isEqualTo(KVisibility.INTERNAL)
	}

	/**
	 * Each train owns its own [Engine]: two trains must not share the propulsion process that
	 * drives their kinematic [cz.ksimulantenbande.kdisco.Variable]s.
	 */
	@Test
	@DisplayName("distinct trains hold distinct engines")
	fun distinctTrainsHoldDistinctEngines() {
		val firstTrain = Train(createMockSimulationContext(), createTimetable())
		val secondTrain = Train(createMockSimulationContext(), createTimetable())

		assertThat(engineOf(firstTrain)).isNotSameInstanceAs(engineOf(secondTrain))
	}

	/**
	 * [engineOf] reflects into the same private field every time; repeated calls on the same
	 * train must return the identical [Engine] instance, not a fresh lookup that happens to be
	 * equal.
	 */
	@Test
	@DisplayName("repeated engineOf calls on the same train return the same instance")
	fun engineOfIsStableAcrossCalls() {
		val train = Train(createMockSimulationContext(), createTimetable())

		assertThat(engineOf(train)).isSameInstanceAs(engineOf(train))
	}

	private fun createTimetable(): Timetable {
		val origin = mockk<cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut>(relaxed = true)
		val destination = mockk<cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut>(relaxed = true)
		every { origin.name } returns "A"
		every { destination.name } returns "B"
		return Timetable(origin, destination, Time(0.0), Time(100.0), 10.0)
	}
}
