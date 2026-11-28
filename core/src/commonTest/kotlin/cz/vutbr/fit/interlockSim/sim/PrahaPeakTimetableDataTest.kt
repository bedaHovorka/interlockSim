/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 9B SP13.1 (Issue #1489): data test of the Praha hl.n. peak-hour timetable.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isBetween
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.PrahaPeakTimetable
import cz.vutbr.fit.interlockSim.testutil.PrahaPeakTimetable.Category
import cz.vutbr.fit.interlockSim.testutil.PrahaPeakTimetable.Kind
import cz.vutbr.fit.interlockSim.testutil.PrahaPeakTimetable.PlannedTrain
import cz.vutbr.fit.interlockSim.testutil.PrahaPeakTimetable.TimeBasis
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Data test of [PrahaPeakTimetable] (Goal 9B SP13.1, Issue #1489) on the Praha Station variant
 * (`praha-hlavni-nadrazi-station.xml`, the network of the Praha examples).
 *
 * It checks what `docs/PRAHA_PEAK_TIMETABLE.md` states: every train's InOut pair is switch-legal
 * ([SwitchLegalPairs.accepts], the rule behind `ShuntingLoop.switchLegalPairsOnly`), every planned
 * station track is a [Station.platformTracks] name and lies on a switch-legal route of the pair,
 * no two trains stand on one track at once, the train count and the trains present at once lie in
 * the stated (estimate) ranges, the dwell times follow the stated (estimate) ranges, and the
 * turnaround share is the one the document gives. Runs in `commonTest` (JVM and `linuxX64`).
 */
class PrahaPeakTimetableDataTest : CommonKoinTestBase() {
	private companion object {
		/** Trains in the list (the document's train list). */
		const val TRAINS: Int = 24

		/** Real terminating trains in the list, modelled as through trains (the document's share: 14 of 24). */
		const val TURNAROUNDS: Int = 14

		const val SECONDS_PER_MINUTE: Double = 60.0

		/** PID booklet 221, weekday 07:00-08:00: departures from Praha hl.n. southbound (S9 and the :46 R49). */
		val PID_SOUTHBOUND_DEPARTURES: List<Int> = listOf(1, 21, 31, 46, 51)

		/** PID booklet 221, weekday 07:00-08:00: arrivals at Praha hl.n. from Vršovice. */
		val PID_ARRIVALS_FROM_VRSOVICE: List<Int> = listOf(5, 25, 38, 40, 55)
	}

	private val trains: List<PlannedTrain> = PrahaPeakTimetable.trains

	private fun load(): DefaultSimulationContext {
		val ctx =
			CommonTestFixtures
				.parseSimulationContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML, get())
				.tracked()
		ctx.getInOuts()
		return ctx
	}

	private fun label(train: PlannedTrain): String = "#${train.number} ${train.line} ${train.entry} -> ${train.exit}"

	@Test
	fun theListHasTheDocumentedTrainCountInsideTheTargetRange() {
		assertThat(trains.size, name = "trains in the peak hour").isEqualTo(TRAINS)
		assertThat(trains.size, name = "trains per hour (target range, estimate)")
			.isBetween(PrahaPeakTimetable.TARGET_TRAINS_PER_HOUR_MIN, PrahaPeakTimetable.TARGET_TRAINS_PER_HOUR_MAX)
		assertThat(trains.map { it.number }, name = "running numbers").containsExactly(*(1..TRAINS).toList().toTypedArray())
		val outsideWindow =
			trains.filterNot {
				it.arrivalSecond >= 0.0 && it.arrivalSecond < PrahaPeakTimetable.WINDOW_SECONDS
			}
		assertThat(outsideWindow.map(::label), name = "trains arriving outside 07:00-08:00").isEmpty()
	}

	@Test
	fun everyTrainHasASwitchLegalOppositeSidePairAndNoBypassInOut() {
		val ctx = load()
		val legal = SwitchLegalPairs(ctx)
		val inOuts = ctx.getInOuts().associateBy { it.name }
		val bypass = setOf(PrahaPeakTimetable.N_BYPASS, PrahaPeakTimetable.S_BYPASS)

		val unknown = trains.filter { it.entry !in inOuts || it.exit !in inOuts }
		assertThat(unknown.map(::label), name = "trains naming an InOut the network lacks").isEmpty()
		val sameSide =
			trains.filter {
				inOuts
					.getValue(
						it.entry
					).getOrientation() == inOuts.getValue(it.exit).getOrientation()
			}
		assertThat(sameSide.map(::label), name = "trains whose entry and exit are on one side").isEmpty()
		val illegal = trains.filterNot { legal.accepts(inOuts.getValue(it.entry), inOuts.getValue(it.exit)) }
		assertThat(illegal.map(::label), name = "trains without a switch-legal route").isEmpty()
		val viaBypass = trains.filter { it.entry in bypass || it.exit in bypass }
		assertThat(viaBypass.map(::label), name = "trains using a fictional bypass InOut").isEmpty()
	}

	@Test
	fun everyPlannedTrackExistsAndLiesOnASwitchLegalRouteOfThePair() {
		val ctx = load()
		val tracks = Station.from(ctx).platformTracks.associateBy { it.name }
		val inOuts = ctx.getInOuts().associateBy { it.name }

		val unknown = trains.filter { it.plannedTrack !in tracks }
		assertThat(unknown.map { "${label(it)} on ${it.plannedTrack}" }, name = "planned tracks not in the Station").isEmpty()

		val offRoute =
			trains.filterNot { train ->
				val track = tracks.getValue(train.plannedTrack).block
				ctx
					.getRouteFinder()
					.findRoutes(inOuts.getValue(train.entry).staticRef, inOuts.getValue(train.exit).staticRef, ctx)
					.any { route -> route.segments.any { it === track || it === track.staticRef } }
			}
		assertThat(offRoute.map { "${label(it)} on ${it.plannedTrack}" }, name = "planned tracks on no legal route").isEmpty()
	}

	@Test
	fun sourcedTimesAreTheBookletDeparturesSouthboundAndTheBookletArrivalsNorthbound() {
		val departureSourced = trains.filter { it.timeBasis == TimeBasis.DEPARTURE_SOURCED }
		val arrivalSourced = trains.filter { it.timeBasis == TimeBasis.ARRIVAL_SOURCED }

		assertThat(departureSourced.map { it.departureMinute }.sorted(), name = "sourced departure minutes")
			.isEqualTo(PID_SOUTHBOUND_DEPARTURES)
		assertThat(
			departureSourced
				.filterNot {
					it.entry.startsWith("N-") && it.exit.startsWith("S-Vrs")
				}.map(::label),
			name = "departure-sourced trains not southbound to Vršovice"
		).isEmpty()
		assertThat(arrivalSourced.map { it.arrivalMinute }.sorted(), name = "sourced arrival minutes")
			.isEqualTo(PID_ARRIVALS_FROM_VRSOVICE)
		assertThat(
			arrivalSourced
				.filterNot {
					it.entry.startsWith("S-Vrs")
				}.map(::label),
			name = "arrival-sourced trains not from Vršovice"
		).isEmpty()
	}

	@Test
	fun everyReuseOfAPlannedTrackLeavesTheMinimumGap() {
		val gap = PrahaPeakTimetable.MIN_TRACK_REUSE_GAP_MINUTES * SECONDS_PER_MINUTE
		val tooClose =
			trains.groupBy { it.plannedTrack }.flatMap { (track, onTrack) ->
				val sorted = onTrack.sortedBy { it.arrivalSecond }
				sorted.zipWithNext().filter { (a, b) -> b.arrivalSecond < a.departureSecond + gap }.map { (a, b) ->
					"$track: ${label(a)} until ${a.departureSecond}, ${label(b)} from ${b.arrivalSecond}"
				}
			}
		assertThat(tooClose, name = "planned-track reuses closer than the minimum gap").isEmpty()
	}

	@Test
	fun presenceStaysInTheTargetRangeAndWithinTheTopologyCapacity() {
		val capacity = Station.from(load()).topologyCapacity
		// The document's section 3 names the capacity (9: k1-k8 plus the bypass track k9); pin it so a
		// topology-derivation change fails here and not only against the peak presence.
		assertThat(capacity, name = "topology capacity (the document's 9 station tracks)").isEqualTo(9)
		// Departures sort before arrivals at the same second: the track is free again.
		val events =
			trains
				.flatMap {
					listOf(it.arrivalSecond to 1, it.departureSecond to -1)
				}.sortedWith(compareBy({ it.first }, { it.second }))
		val peak = events.runningFold(0) { present, event -> present + event.second }.max()
		assertThat(peak, name = "trains present at once (target range, estimate)")
			.isBetween(PrahaPeakTimetable.TARGET_PRESENT_AT_ONCE_MIN, PrahaPeakTimetable.TARGET_PRESENT_AT_ONCE_MAX)
		assertThat(peak, name = "trains present at once against the topology capacity")
			.isLessThanOrEqualTo(capacity)
	}

	@Test
	fun dwellTimesFollowTheDocumentedRangesAndTheTurnaroundShareIsTheDocumentedOne() {
		fun minutes(train: PlannedTrain): Double = train.dwellSeconds / SECONDS_PER_MINUTE

		val outOfRange =
			trains.filterNot { train ->
				when {
					train.kind == Kind.TURNAROUND -> minutes(train) in 15.0..60.0
					train.category == Category.OS_SP -> minutes(train) in 1.0..3.0
					else -> minutes(train) in 3.0..10.0
				}
			}
		assertThat(
			outOfRange.map {
				"${label(it)} dwell ${minutes(it)} min"
			},
			name = "dwell times outside the ranges"
		).isEmpty()
		assertThat(
			trains.count {
				it.kind == Kind.TURNAROUND
			},
			name = "real turnarounds modelled as through"
		).isEqualTo(TURNAROUNDS)
	}

	@Test
	fun trainSpecsCarryEveryTrainInArrivalOrder() {
		val specs = PrahaPeakTimetable.trainSpecs()

		assertThat(specs.size, name = "specs").isEqualTo(TRAINS)
		assertThat(specs.map { it.inTime }, name = "spec arrival order").isEqualTo(specs.map { it.inTime }.sorted())
		assertThat(
			specs.map { it.length }.distinct(),
			name = "train lengths"
		).containsExactly(PrahaPeakTimetable.TRAIN_LENGTH)
		// Sorted multisets, not sets: a duplicated pair on either side of the pin would now fail.
		assertThat(
			specs.map { it.inName to it.outName }.sortedWith(compareBy({ it.first }, { it.second })),
			name = "spec pairs"
		).isEqualTo(
			trains.map { it.entry to it.exit }.sortedWith(compareBy({ it.first }, { it.second }))
		)
	}
}
