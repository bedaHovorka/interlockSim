/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.domain

import kotlin.math.sqrt

/*
 * Railway physics and domain constants.
 *
 * These constants define physical constraints and typical values for railway
 * network simulation, based on real-world Czech Railways (ČD) standards.
 *
 * ## Track Geometry
 * - [MIN_TRACK_LENGTH]: Minimum allowed track section length
 * - [COMMON_TRACK_LENGTH]: Typical track section length
 *
 * ## Speed Limits
 * - [COMMON_MAX_SPEED]: Common maximum speed for track sections
 * - [ABSOLUTE_MAX_SPEED]: Absolute maximum speed (emergency limit)
 * - [MINIMAL_MAX_SPEED]: Minimum allowed maximum speed
 *
 * ## Rail Switch Speeds
 * - [COMMON_BRANCH_SPEED]: Speed through switch branch track
 * - [COMMON_MAIN_SPEED]: Speed through switch main track
 *
 * ## Train Physics
 * - [MAXIMAL_TRAIN_ACCELERATION]: Maximum train acceleration
 * - [MINIMAL_TRAIN_DECELERATION]: Minimum train deceleration (braking)
 * - [brakingDistanceFrom] / [brakingSpeedWithin]: single service-braking law (Issue #1056)
 *
 * @since 0.1-bachelor
 */

// ----- Track Geometry -----

/** Minimum allowed track length in meters */
const val MIN_TRACK_LENGTH = 5.0

/** Typical track length in meters */
const val COMMON_TRACK_LENGTH = 100.0

// ----- Speed Limits -----

/** Common maximum speed for track sections (m/s) - approximately 86 km/h */
const val COMMON_MAX_SPEED = 24.0

/** Absolute maximum speed for any track element (m/s) - approximately 324 km/h */
const val ABSOLUTE_MAX_SPEED = 90.0

/** Minimum allowed maximum speed (m/s) - approximately 7.2 km/h */
const val MINIMAL_MAX_SPEED = 2.0

// ----- Rail Switch Speeds -----

/** Common speed through branch track of rail switch (m/s) - approximately 47 km/h */
const val COMMON_BRANCH_SPEED = 13

/** Common speed through main track of rail switch (m/s) - approximately 108 km/h */
const val COMMON_MAIN_SPEED = 30

// ----- Train Physics -----

/** Maximum train acceleration in m/s² */
const val MAXIMAL_TRAIN_ACCELERATION = 4

/** Minimum train deceleration in m/s² (negative value for braking) */
const val MINIMAL_TRAIN_DECELERATION = -3

/**
 * Service-braking magnitude (m/s²) implied by [MINIMAL_TRAIN_DECELERATION].
 *
 * Always positive so kinematics can use `|a|` without re-deriving the sign at each call site.
 */
val SERVICE_BRAKING_DECELERATION_MPS2: Double = -MINIMAL_TRAIN_DECELERATION.toDouble()

/**
 * Textbook stopping distance (metres) from [speedMps] at the service-braking rate
 * [MINIMAL_TRAIN_DECELERATION]: `s = v² / (2 · |a|)`.
 *
 * Non-positive speeds yield `0.0` (already stopped — no distance needed).
 *
 * The inverse of [brakingSpeedWithin]. Used by [cz.vutbr.fit.interlockSim.sim.Train]'s
 * motor (Issues #1014, #1057) so the braking-room margin and the reactive decider share
 * one formula rather than two literals of `3` that only agree by coincidence (Issue #1056).
 */
fun brakingDistanceFrom(speedMps: Double): Double {
	if (speedMps <= 0.0) return 0.0
	return (speedMps * speedMps) / (2.0 * SERVICE_BRAKING_DECELERATION_MPS2)
}

/**
 * Highest speed (m/s) from which the train can still stop within [distanceMetres] at the
 * service-braking rate [MINIMAL_TRAIN_DECELERATION]: `v = sqrt(2 · |a| · s)`.
 *
 * Non-positive distances yield `0.0` (no room left to stop from any speed).
 *
 * The inverse of [brakingDistanceFrom]. Used by
 * [cz.vutbr.fit.interlockSim.sim.ReactiveTrainDecider] for the Výstraha speed cap so a
 * change to [MINIMAL_TRAIN_DECELERATION] reaches every consumer of the braking law
 * (Issue #1056).
 */
fun brakingSpeedWithin(distanceMetres: Double): Double {
	if (distanceMetres <= 0.0) return 0.0
	return sqrt(2.0 * SERVICE_BRAKING_DECELERATION_MPS2 * distanceMetres)
}
