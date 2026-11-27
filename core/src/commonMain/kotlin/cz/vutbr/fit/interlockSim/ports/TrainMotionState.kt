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

/**
 * The motion state of one train in the published train sample ([TrainPositionReading.state]).
 *
 * Sampled on the simulation thread together with the rest of the reading (Issue #1472), from live
 * train state, in this order — the first that holds wins:
 * 1. [NOT_ENTERED] — the train's front occupies no track section yet (approved, not yet entered).
 * 2. [STATION_DWELL] — a commanded station dwell is in progress (`Train.isStationDwelling`).
 * 3. [STANDING] — the velocity is exactly 0.0 for any other reason, typically held at a signal.
 * 4. [MOVING] — otherwise.
 *
 * A value type only (#1467 spec §2): it names a fact, it carries no live reference.
 *
 * @since Issue #1472 (Goal 9B SP4.6)
 */
enum class TrainMotionState {
	NOT_ENTERED,
	STATION_DWELL,
	STANDING,
	MOVING
}
