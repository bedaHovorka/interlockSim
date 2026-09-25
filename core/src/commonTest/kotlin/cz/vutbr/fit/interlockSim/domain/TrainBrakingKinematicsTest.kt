/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1056 — single service-braking law shared by Motor and ReactiveTrainDecider.
 */
package cz.vutbr.fit.interlockSim.domain

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import kotlin.test.Test

/**
 * Unit tests for the shared service-braking kinematics in PhysicsConstants
 * (`brakingDistanceFrom` / `brakingSpeedWithin`).
 *
 * These two functions are the single source of the textbook braking law
 * `v² = 2 · |a| · s` over [MINIMAL_TRAIN_DECELERATION]. Train Motor and
 * ReactiveTrainDecider must both call them so a change to the deceleration
 * constant reaches every consumer (Issue #1056).
 *
 * Pure common-platform tests (KMP `commonTest`) — no MockK/JUnit 5 — so they run on
 * both JVM and native targets.
 *
 */
class TrainBrakingKinematicsTest {
	@Test
	fun `brakingDistanceFrom is v squared over twice the service braking magnitude`() {
		// |a| = 3 m/s²; v = 6 → s = 36 / 6 = 6.0 m
		assertThat(brakingDistanceFrom(6.0)).isCloseTo(6.0, delta = 1e-12)
		// v = 0 → already stopped
		assertThat(brakingDistanceFrom(0.0)).isEqualTo(0.0)
		assertThat(brakingDistanceFrom(-1.0)).isEqualTo(0.0)
	}

	@Test
	fun `brakingSpeedWithin is sqrt of twice a times s`() {
		// |a| = 3 m/s²; s = 6 → v = sqrt(36) = 6.0 m/s (same numbers ReactiveTrainDeciderTest uses)
		assertThat(brakingSpeedWithin(6.0)).isCloseTo(6.0, delta = 1e-12)
		assertThat(brakingSpeedWithin(0.0)).isEqualTo(0.0)
		assertThat(brakingSpeedWithin(-5.0)).isEqualTo(0.0)
	}

	@Test
	fun `distance and speed forms are exact inverses over the shared deceleration`() {
		val speeds = listOf(1.0, 6.0, 13.89, 27.78, 40.0)
		for (v in speeds) {
			assertThat(brakingSpeedWithin(brakingDistanceFrom(v))).isCloseTo(v, delta = 1e-9)
		}
		val distances = listOf(1.0, 6.0, 30.0, 100.0, 1000.0)
		for (s in distances) {
			assertThat(brakingDistanceFrom(brakingSpeedWithin(s))).isCloseTo(s, delta = 1e-9)
		}
	}

	@Test
	fun `service braking magnitude matches the domain deceleration constant`() {
		assertThat(SERVICE_BRAKING_DECELERATION_MPS2)
			.isEqualTo(-MINIMAL_TRAIN_DECELERATION.toDouble())
		assertThat(SERVICE_BRAKING_DECELERATION_MPS2).isEqualTo(3.0)
	}
}
