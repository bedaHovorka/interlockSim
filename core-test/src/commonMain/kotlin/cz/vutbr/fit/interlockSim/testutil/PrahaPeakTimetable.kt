/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 9B SP13.1 (Issue #1489): the peak-hour timetable for Praha hl.n.
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop

/**
 * The peak-hour timetable for Praha hlavní nádraží (Goal 9B SP13.1, Issue #1489): one weekday hour,
 * 07:00-08:00, derived from the public timetable and scaled down by the track-count ratio, 9 model
 * station tracks against 22 real ones. Written by the railway-civil-engineer as test data; the
 * facts, estimates, sources, the scaling and every simplification are in `docs/PRAHA_PEAK_TIMETABLE.md`.
 *
 * The timetable is fixed: the train list, planned times, InOut pairs, planned station tracks and
 * categories never change. The arrival disturbance a traffic seed varies (Issue #1447) is applied
 * around it by the code that runs it, not here.
 *
 * It targets the Station variant `praha-hlavni-nadrazi-station.xml`
 * ([NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML]), the network of the Praha examples. The
 * planned station tracks are the names `cz.vutbr.fit.interlockSim.sim.Station` derives for its
 * platform tracks, `k1` to `k9` in row order (`k9` is the bypass station track, unused here).
 *
 * The data lives in `commonMain` so the data test (`PrahaPeakTimetableDataTest`, `:core`
 * `commonTest`) runs on the JVM and on `linuxX64`, like the `MultiTrainStressSpecsTest` guard.
 * Nothing in production reads it yet; timetable-driven arrivals are developer work named in #1447.
 */
object PrahaPeakTimetable {
	/** Length of every train in meters (simplification: one train length). */
	const val TRAIN_LENGTH: Double = 40.0

	/** Seconds per timetable minute. */
	const val SECONDS_PER_MINUTE: Double = 60.0

	/** Length of the timetable window in seconds: one hour, 07:00-08:00. */
	const val WINDOW_SECONDS: Double = 3600.0

	/** Lower bound of the target train count per hour (estimate; scaled by the track-count ratio). */
	const val TARGET_TRAINS_PER_HOUR_MIN: Int = 20

	/** Upper bound of the target train count per hour (estimate; scaled by the track-count ratio). */
	const val TARGET_TRAINS_PER_HOUR_MAX: Int = 27

	/**
	 * Minimum time between one train leaving a planned station track and the next train arriving on
	 * it, in minutes: the reoccupation time of a platform track (estimate, domain practice). The
	 * departure counts the dwell from the entry InOut, so the gap also covers the run-in time.
	 */
	const val MIN_TRACK_REUSE_GAP_MINUTES: Int = 3

	/** Lower bound of the trains present at once (estimate; scaled by the track-count ratio). */
	const val TARGET_PRESENT_AT_ONCE_MIN: Int = 5

	/** Upper bound of the trains present at once (estimate; scaled by the track-count ratio). */
	const val TARGET_PRESENT_AT_ONCE_MAX: Int = 7

	/** North InOuts of the Praha model (orientation `false`). */
	const val N_LIB_1: String = "N-Lib-1"
	const val N_LIB_2: String = "N-Lib-2"
	const val N_VYS_1: String = "N-Vys-1"
	const val N_VYS_2: String = "N-Vys-2"

	/** North bypass InOut: no real counterpart verified, marked fictional; unused by this timetable. */
	const val N_BYPASS: String = "N-Bypass"

	/** South InOuts of the Praha model (orientation `true`). */
	const val S_VIN_1: String = "S-Vin-1"
	const val S_VIN_2: String = "S-Vin-2"
	const val S_VRS_1: String = "S-Vrs-1"
	const val S_VRS_2: String = "S-Vrs-2"
	const val S_VRS_3: String = "S-Vrs-3"

	/** South bypass InOut: no real counterpart, fictional; unused by this timetable. */
	const val S_BYPASS: String = "S-Bypass"

	/** Train category as the public timetable states it. */
	enum class Category {
		/** Long-distance: Ex, EC. */
		EX_EC,

		/** Fast regional: R. */
		R,

		/** Regional: Os, Sp. */
		OS_SP
	}

	/** What the real train does at Praha hl.n. */
	enum class Kind {
		/** Runs through the station from one side to the other. */
		THROUGH,

		/**
		 * Really terminates (or reverses) and leaves from the side it came from. `ShuntingLoop` pairs
		 * only opposite sides, so it is modelled as a through train with an exit on the opposite side;
		 * it keeps its planned station track (Issue #1495 covers real turnarounds).
		 */
		TURNAROUND
	}

	/** Where a train's time comes from. */
	enum class TimeBasis {
		/** The entry time is an arrival at Praha hl.n. read from PID booklet 221 (trains from Vršovice). */
		ARRIVAL_SOURCED,

		/**
		 * PID booklet 221 gives the departure from Praha hl.n. (southbound, towards Strančice/Benešov):
		 * [PlannedTrain.departureMinute] is that sourced time, and the entry time is that departure
		 * minus the dwell, an estimate.
		 */
		DEPARTURE_SOURCED,

		/** Not read from a timetable: a clock-face offset chosen to spread the arrivals (estimate). */
		ESTIMATE
	}

	/**
	 * One planned train.
	 *
	 * @property number running number within this timetable, 1-based
	 * @property line the public line or train family it stands for, for example `S9` or `R16`
	 * @property arrivalMinute planned arrival at the entry InOut, minutes after 07:00
	 * @property dwellMinutes planned standing time on [plannedTrack] in minutes
	 * @property entry entry InOut name
	 * @property exit exit InOut name; for a [Kind.TURNAROUND] the modelled opposite-side exit
	 * @property plannedTrack planned station track, a `Station` platform-track name
	 * @property timeBasis which of the train's times is read from PID booklet 221, if any: the
	 *   arrival ([TimeBasis.ARRIVAL_SOURCED]), the departure with an estimated entry time
	 *   ([TimeBasis.DEPARTURE_SOURCED]), or none ([TimeBasis.ESTIMATE])
	 */
	data class PlannedTrain(
		val number: Int,
		val line: String,
		val category: Category,
		val kind: Kind,
		val arrivalMinute: Int,
		val dwellMinutes: Int,
		val entry: String,
		val exit: String,
		val plannedTrack: String,
		val timeBasis: TimeBasis,
		val length: Double = TRAIN_LENGTH
	) {
		/** Planned arrival at the entry InOut, simulation seconds after 07:00. */
		val arrivalSecond: Double get() = arrivalMinute * SECONDS_PER_MINUTE

		/** Planned standing time on [plannedTrack] in seconds. */
		val dwellSeconds: Double get() = dwellMinutes * SECONDS_PER_MINUTE

		/** Planned departure from [plannedTrack], minutes after 07:00. */
		val departureMinute: Int get() = arrivalMinute + dwellMinutes

		/** Planned departure from [plannedTrack], seconds after 07:00. */
		val departureSecond: Double get() = arrivalSecond + dwellSeconds

		/** This train as a [MultiTrainLoop.TrainSpec] (entry, exit, arrival time, length). */
		fun toTrainSpec(): MultiTrainLoop.TrainSpec = MultiTrainLoop.TrainSpec(entry, exit, arrivalSecond, length)
	}

	/**
	 * Every train of the peak hour, in [PlannedTrain.number] order: 10 through trains (1-10) and 14
	 * real turnarounds modelled as through trains (11-24). The north side follows the line mapping of
	 * the document: `N-Lib` is the Libeň tunnel (Kolín corridor, S7 towards Český Brod), `N-Vys` the
	 * Holešovice/Vysočany tunnel (S9, S2, R20, R21, EC via Děčín); south `S-Vin` is Vinohrady
	 * tunnel I towards Smíchov (S7, R16, R26), `S-Vrs` tunnels II/III towards Vršovice (S9, S8, R17,
	 * R49).
	 */
	val trains: List<PlannedTrain> =
		listOf(
			PlannedTrain(1, "S9", Category.OS_SP, Kind.THROUGH, 0, 1, N_VYS_1, S_VRS_1, "k5", TimeBasis.DEPARTURE_SOURCED),
			PlannedTrain(2, "S9", Category.OS_SP, Kind.THROUGH, 5, 2, S_VRS_1, N_VYS_1, "k5", TimeBasis.ARRIVAL_SOURCED),
			PlannedTrain(3, "S9", Category.OS_SP, Kind.THROUGH, 19, 2, N_VYS_1, S_VRS_2, "k5", TimeBasis.DEPARTURE_SOURCED),
			PlannedTrain(4, "S9", Category.OS_SP, Kind.THROUGH, 25, 2, S_VRS_2, N_VYS_1, "k5", TimeBasis.ARRIVAL_SOURCED),
			PlannedTrain(5, "S9", Category.OS_SP, Kind.THROUGH, 29, 2, N_VYS_2, S_VRS_1, "k7", TimeBasis.DEPARTURE_SOURCED),
			PlannedTrain(6, "S9", Category.OS_SP, Kind.THROUGH, 49, 2, N_VYS_1, S_VRS_2, "k6", TimeBasis.DEPARTURE_SOURCED),
			PlannedTrain(7, "S9", Category.OS_SP, Kind.THROUGH, 55, 2, S_VRS_1, N_VYS_2, "k8", TimeBasis.ARRIVAL_SOURCED),
			PlannedTrain(8, "S7", Category.OS_SP, Kind.THROUGH, 12, 3, S_VIN_2, N_LIB_2, "k4", TimeBasis.ESTIMATE),
			PlannedTrain(9, "S7", Category.OS_SP, Kind.THROUGH, 42, 3, N_LIB_1, S_VIN_1, "k1", TimeBasis.ESTIMATE),
			PlannedTrain(10, "R49", Category.R, Kind.THROUGH, 42, 4, N_LIB_2, S_VRS_3, "k4", TimeBasis.DEPARTURE_SOURCED),
			PlannedTrain(11, "R49", Category.R, Kind.TURNAROUND, 38, 20, S_VRS_3, N_VYS_1, "k5", TimeBasis.ARRIVAL_SOURCED),
			PlannedTrain(12, "S8", Category.OS_SP, Kind.TURNAROUND, 40, 15, S_VRS_2, N_VYS_2, "k7", TimeBasis.ARRIVAL_SOURCED),
			PlannedTrain(13, "R16", Category.R, Kind.TURNAROUND, 10, 20, S_VIN_1, N_LIB_2, "k3", TimeBasis.ESTIMATE),
			PlannedTrain(14, "R26", Category.R, Kind.TURNAROUND, 48, 20, S_VIN_2, N_LIB_1, "k1", TimeBasis.ESTIMATE),
			PlannedTrain(15, "R17", Category.R, Kind.TURNAROUND, 18, 20, S_VRS_1, N_LIB_2, "k4", TimeBasis.ESTIMATE),
			PlannedTrain(16, "R20", Category.R, Kind.TURNAROUND, 2, 20, N_VYS_1, S_VRS_3, "k6", TimeBasis.ESTIMATE),
			PlannedTrain(17, "EC", Category.EX_EC, Kind.TURNAROUND, 22, 20, N_VYS_2, S_VRS_2, "k8", TimeBasis.ESTIMATE),
			PlannedTrain(18, "R19", Category.R, Kind.TURNAROUND, 13, 25, N_LIB_1, S_VIN_1, "k1", TimeBasis.ESTIMATE),
			PlannedTrain(19, "R18", Category.R, Kind.TURNAROUND, 35, 25, N_LIB_2, S_VIN_1, "k3", TimeBasis.ESTIMATE),
			PlannedTrain(20, "R10", Category.R, Kind.TURNAROUND, 48, 20, N_LIB_1, S_VIN_1, "k2", TimeBasis.ESTIMATE),
			PlannedTrain(21, "R9", Category.R, Kind.TURNAROUND, 28, 15, N_LIB_1, S_VIN_1, "k2", TimeBasis.ESTIMATE),
			PlannedTrain(22, "S2", Category.OS_SP, Kind.TURNAROUND, 8, 15, N_VYS_2, S_VRS_1, "k7", TimeBasis.ESTIMATE),
			PlannedTrain(23, "R41", Category.R, Kind.TURNAROUND, 55, 15, N_LIB_2, S_VIN_2, "k4", TimeBasis.ESTIMATE),
			PlannedTrain(24, "R21", Category.R, Kind.TURNAROUND, 26, 20, N_VYS_1, S_VRS_3, "k6", TimeBasis.ESTIMATE)
		)

	/** The trains as [MultiTrainLoop.TrainSpec]s in arrival order. */
	fun trainSpecs(): List<MultiTrainLoop.TrainSpec> = trains.sortedBy { it.arrivalSecond }.map { it.toTrainSpec() }
}
