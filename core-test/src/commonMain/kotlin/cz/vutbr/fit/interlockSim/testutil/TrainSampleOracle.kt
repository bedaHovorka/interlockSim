/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.TrainMotionState
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.Train
import cz.vutbr.fit.interlockSim.util.BlockIdentity

/**
 * Issue #1472 (Goal 9B SP4.6): the live-state oracle for one train of the published train sample.
 *
 * Reads every field of [TrainPositionReading] straight from the live [Train] — not through
 * [DefaultNetworkPerceptionPort] — so [PlanningSnapshotLiveComparator] compares the published
 * sample with an independent source. Call it on the simulation thread only, at the first point
 * after publication (#1467 spec rule P3), where live state still equals the published snapshot.
 *
 * The [TrainMotionState] order is the one [TrainMotionState] documents: no front section first,
 * then a station dwell, then standing (velocity exactly 0.0), else moving.
 */
object TrainSampleOracle {
	fun liveSample(train: Train): TrainPositionReading =
		TrainPositionReading(
			trainId = train.name,
			velocity = train.getVelocity(),
			acceleration = train.getAcceleration(),
			totalDistance = train.totalDistance,
			frontSectionName =
				(train.frontSection?.getTrackBlock() as? DynamicTrackBlock)?.let(BlockIdentity::stableBlockId),
			length = train.trainLength,
			state = liveState(train)
		)

	private fun liveState(train: Train): TrainMotionState =
		when {
			train.frontSection == null -> TrainMotionState.NOT_ENTERED
			train.isStationDwelling -> TrainMotionState.STATION_DWELL
			train.getVelocity() == 0.0 -> TrainMotionState.STANDING
			else -> TrainMotionState.MOVING
		}

	/**
	 * Compares the published train [sample] of [tick] with the [live] approved trains: first the
	 * train list (ids, in order), then every field of each reading against [liveSample]. Returns one
	 * message per mismatch, empty when the sample equals live state. Simulation thread only.
	 */
	fun compareWithLive(
		tick: Long,
		sample: List<TrainPositionReading>,
		live: List<Train>
	): List<String> {
		val sampleIds = sample.map { it.trainId }
		val liveIds = live.map { it.name }
		if (sampleIds != liveIds) return listOf("tick $tick: trains $sampleIds != live $liveIds")
		return sample.zip(live).mapNotNull { (reading, train) ->
			fieldDiff(reading, liveSample(train))?.let { "tick $tick ${train.name}: $it" }
		}
	}

	/**
	 * Names every field in which [published] differs from [live], or returns `null` when they are
	 * equal. A per-field message makes a failure say what tore, not only that something did.
	 */
	fun fieldDiff(
		published: TrainPositionReading,
		live: TrainPositionReading
	): String? {
		val fields =
			listOf(
				Triple("trainId", published.trainId, live.trainId),
				Triple("velocity", published.velocity, live.velocity),
				Triple("acceleration", published.acceleration, live.acceleration),
				Triple("totalDistance", published.totalDistance, live.totalDistance),
				Triple("frontSectionName", published.frontSectionName, live.frontSectionName),
				Triple("length", published.length, live.length),
				Triple("state", published.state, live.state)
			)
		val differing = fields.filter { (_, p, l) -> p != l }
		check(differing.isNotEmpty() == (published != live)) { "fieldDiff misses a TrainPositionReading field" }
		return differing
			.takeIf { it.isNotEmpty() }
			?.joinToString { (name, p, l) -> "$name published=$p live=$l" }
	}
}
