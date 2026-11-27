/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.PlanningSnapshotLiveComparator
import cz.vutbr.fit.interlockSim.testutil.TrainSampleOracle
import cz.vutbr.fit.interlockSim.testutil.runSampled
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.koin.core.component.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Issue #1472 (Goal 9B SP4.6, #1467 spec "Named tests"): a reader on another thread reads the train
 * sample during a Praha run and never sees a value that is not the one the simulation thread
 * published for that tick.
 *
 * The tick stamp is [cz.vutbr.fit.interlockSim.ports.DispatchLoopSnapshot.tick]. Checking "the
 * same stamp for all trains" would be trivially true with one published reference, so each
 * off-thread read is checked against a simulation-thread recorder keyed by tick instead:
 *
 * - **Recorder** (simulation thread): a control-step listener wrapped by
 *   [PlanningSnapshotLiveComparator], installed after [wireSynchronousDispatcher], so under spec rule
 *   P3 the comparator runs first after the publication and compares each reading with the live train
 *   ([TrainSampleOracle]); the recorder then keeps, per tick, the published train-sample reference
 *   and a structural copy.
 * - **Reader** (its own thread): reads [ShuntingLoop.latestDispatchLoopSnapshot] in a tight loop
 *   while the simulation thread integrates train physics, and keeps every read that differs from
 *   the one before (another tick, or another reference within the same tick).
 * - **After the run:** every kept read with tick N must be the recorded reference of tick N and
 *   equal its copy; reads of tick 0 must be empty; every recorded reference must still equal its
 *   copy, so the published value did not change after publication.
 *
 * No wall-clock assertion: on every [HANDSHAKE_EVERY]th tick the recorder waits — bounded by
 * [HANDSHAKE_TIMEOUT_SECONDS] — until the reader has seen that tick, which guarantees that the reader
 * reads during the run rather than only before or after it. A missed handshake only weakens the
 * coverage, and the coverage is asserted by count ([MIN_TICKS_READ_WITH_TRAINS]), not by time.
 * Mismatches are collected and asserted after the run: a throw inside the listener would end the run
 * (`MainProcessFailureRunEndTest`).
 */
@Tag("integration-test")
class TrainSampleNoTornTickStressTest : KoinTestBase() {
	private class Recorded(
		val reference: List<TrainPositionReading>,
		val copy: List<TrainPositionReading>
	)

	private class Read(
		val tick: Long,
		val sample: List<TrainPositionReading>
	)

	@RepeatedTest(REPEATS)
	fun offThreadReadsOfThePrahaTrainSampleAreAlwaysTheSampleOfTheirTick() {
		val ctx: DefaultSimulationContext =
			CommonTestFixtures.parseSimulationContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML, get()).tracked()
		ctx.getInOuts() // initialize dynamic wrappers
		val loop =
			ShuntingLoop(
				ctx,
				END_TIME,
				maxConcurrentTrains = TRAINS,
				seed = SEED,
				maxTrains = TRAINS,
				switchLegalPairsOnly = true
			)
		wireSynchronousDispatcher(ctx, loop)
		val dispatcherStep = checkNotNull(loop.controlStepListener)

		val recorded = ConcurrentHashMap<Long, Recorded>()
		val comparator = PlanningSnapshotLiveComparator(ctx, loop)
		val lastTickSeenByReader = AtomicLong(-1L)
		val readerDone = AtomicBoolean(false)
		val readerFailure = AtomicReference<Throwable?>(null)
		val reads = ArrayList<Read>()

		val reader =
			Thread({
				try {
					var lastTick = -1L
					var lastSample: List<TrainPositionReading>? = null
					while (!readerDone.get()) {
						val snapshot = loop.latestDispatchLoopSnapshot()
						val sample = snapshot.snapshot.trainPositions
						if (snapshot.tick != lastTick || sample !== lastSample) {
							reads += Read(snapshot.tick, sample)
							lastTick = snapshot.tick
							lastSample = sample
						}
						lastTickSeenByReader.set(snapshot.tick)
						Thread.onSpinWait()
					}
				} catch (t: Throwable) {
					readerFailure.set(t)
				}
			}, "train-sample-reader")
		reader.isDaemon = true

		loop.controlStepListener =
			comparator.wrap(
				ControlStepListener {
					val published = loop.latestDispatchLoopSnapshot()
					val sample = published.snapshot.trainPositions
					recorded[published.tick] = Recorded(sample, sample.map { it.copy() })
					if (published.tick % HANDSHAKE_EVERY == 0L) awaitReader(lastTickSeenByReader, published.tick)
					dispatcherStep.onControlStep()
				}
			)
		ctx.setMainProcess(loop)

		reader.start()
		try {
			runSampled(ctx) { }
		} finally {
			readerDone.set(true)
			reader.join(TimeUnit.SECONDS.toMillis(READER_JOIN_TIMEOUT_SECONDS))
		}

		assertThat(reader.isAlive, name = "reader thread still running").isFalse()
		assertThat(readerFailure.get(), name = "reader thread failure").isNull()
		assertThat(comparator.mismatches, name = "published snapshot vs live mismatches").isEmpty()

		val tornReads =
			reads.mapNotNull { read ->
				if (read.tick == 0L) {
					read.sample.takeIf { it.isNotEmpty() }?.let { "tick 0 read carries trains $it" }
				} else {
					val expected = recorded[read.tick]
					when {
						expected == null -> "tick ${read.tick} read, but the simulation thread recorded no such tick"
						read.sample !== expected.reference -> "tick ${read.tick}: read a sample that is not the published one"
						read.sample != expected.copy -> "tick ${read.tick}: read ${read.sample} != recorded ${expected.copy}"
						else -> null
					}
				}
			}
		assertThat(tornReads, name = "off-thread reads not equal to their tick's sample").isEmpty()

		val changedAfterPublication =
			recorded.filterValues { it.reference != it.copy }.keys.sorted()
		assertThat(changedAfterPublication, name = "ticks whose published sample changed later").isEmpty()

		val ticksReadWithTrains = reads.filter { it.tick > 0L && it.sample.isNotEmpty() }.map { it.tick }.toSet()
		assertThat(ticksReadWithTrains.size, name = "distinct ticks read off-thread with trains")
			.isGreaterThanOrEqualTo(MIN_TICKS_READ_WITH_TRAINS)
	}

	/**
	 * Waits on the simulation thread until the reader has seen [tick], at most
	 * [HANDSHAKE_TIMEOUT_SECONDS]; returns quietly on time-out (coverage is asserted by count).
	 */
	private fun awaitReader(
		lastTickSeenByReader: AtomicLong,
		tick: Long
	) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HANDSHAKE_TIMEOUT_SECONDS)
		while (lastTickSeenByReader.get() < tick && System.nanoTime() < deadline) {
			LockSupport.parkNanos(HANDSHAKE_POLL_NANOS)
		}
	}

	private companion object {
		const val REPEATS = 5
		const val END_TIME: Long = 600L
		const val SEED: Long = 1472L
		const val TRAINS = 6
		const val HANDSHAKE_EVERY = 5L
		const val HANDSHAKE_TIMEOUT_SECONDS = 10L
		const val HANDSHAKE_POLL_NANOS = 100_000L
		const val READER_JOIN_TIMEOUT_SECONDS = 30L
		const val MIN_TICKS_READ_WITH_TRAINS = 10
	}
}
