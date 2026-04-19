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
