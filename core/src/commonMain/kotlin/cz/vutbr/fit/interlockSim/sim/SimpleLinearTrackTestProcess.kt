/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.hovorka.kdisco.Process
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent

/**
 * Focused Interlocking coordinator for individual-train lifecycle tests on a
 * linear track topology — issue #366.
 *
 * Unlike [ShuntingLoop] (multi-train dispatcher tied to `vyhybna.xml`), this
 * process does **not** drive dynamic path reservations from polling semaphores.
 * Instead, it simply:
 *
 *  - Creates [Train] instances from caller-supplied [TrainSpec]s at their
 *    timetable entry times.
 *  - Queues and approves up to [MAX_TRAINS] concurrently active trains.
 *  - Tracks lifecycle counters matching the #365 instrumentation surface so
 *    tests can assert on real metrics (no log parsing).
 *
 * Tests wanting to exercise "path reserved" vs "path blocked" semantics should
 * pre-reserve paths via [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService]
 * **before** `context.run()` — the process itself does not reserve forward
 * paths. This keeps the coordinator small and the scenarios explicit.
 *
 * ## Downstream of #365
 *
 * Per the 2026-04-14 backlog election
 * (docs/election/2026-04-14-backlog-election.md), this class consumes the
 * #365 instrumentation pattern. Counter signatures are kept identical:
 *
 *  - [getTrainsEntered]
 *  - [getTrainsExited]
 *  - [getMaxConcurrentTrains]
 *  - [getBlockTransitions]
 *  - [getAllBlockTransitions]
 *
 * Counters are incremented from existing lifecycle sites only (no extra
 * polling loop is introduced).
 *
 * @see ShuntingLoop pattern source for instrumentation
 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/366">Issue #366</a>
 */
class SimpleLinearTrackTestProcess(
	context: SimulationContext,
	private val endTime: Long,
	private val trainSpecs: List<TrainSpec> = emptyList(),
	private val onTrainCreated: (Train) -> Unit = {}
) : Interlocking(context),
	KoinComponent {
	companion object {
		private val logger = KotlinLogging.logger {}

		/** Physical cap for approved-trains concurrency (shared with ShuntingLoop). */
		internal const val MAX_TRAINS: Int = 2

		/** Report types enabled by individual-train lifecycle scenarios. */
		internal val ENABLED_REPORT_TYPES = arrayOf(
			ReportType.TRAIN_APPROVED,
			ReportType.TRAIN_EVENTS,
			ReportType.TRAIN_CONTINUOUS
		)
	}

	/**
	 * Minimal specification of a test train to inject.
	 *
	 * InOuts are resolved by [DynamicInOut.name] against the simulation
	 * context — the caller does not need to manage wrapper identity.
	 *
	 * @property inName entry InOut name
	 * @property outName exit InOut name
	 * @property inTime simulation time at which the train is generated
	 * @property outTime simulation time at which the train is scheduled to exit
	 * @property length train length (meters)
	 */
	data class TrainSpec(
		val inName: String,
		val outName: String,
		val inTime: Double,
		val outTime: Double,
		val length: Double = 40.0
	)

	private val unapprowedTrains: ArrayDeque<Train> = ArrayDeque()
	private val approwedTrains: MutableList<Train> = mutableListOf()
	private val generator: InjectingGenerator = InjectingGenerator(context)

	// Test-observability counters (#365 pattern, per #366).
	private var trainsEnteredCount: Int = 0
	private var trainsExitedCount: Int = 0
	private var maxConcurrentTrainsCount: Int = 0
	private val blockTransitionsByTrain: MutableMap<String, Int> = mutableMapOf()

	/**
	 * Generator that injects caller-supplied trains deterministically at their
	 * timetable entry times rather than randomly.
	 */
	private inner class InjectingGenerator(
		context: SimulationEnvironment
	) : Generator(context) {
		private val remaining: ArrayDeque<TrainSpec> = ArrayDeque(trainSpecs)

		override suspend fun startAction() {
			super.startAction()
		}

		override suspend fun iteration() {
			if (remaining.isEmpty()) {
				terminate()
				return
			}
			val spec = remaining.first()
			val now = time()
			if (now < spec.inTime) {
				// Wait until the train is due; interLoopSleep will advance time.
				return
			}
			remaining.removeFirst()
			val inOuts = env.getInOuts()
			val inIo = inOuts.find { it.name == spec.inName }
				?: error("No DynamicInOut named '${spec.inName}' in context")
			val outIo = inOuts.find { it.name == spec.outName }
				?: error("No DynamicInOut named '${spec.outName}' in context")
			val timetable = Timetable(inIo, outIo, Time(spec.inTime), Time(spec.outTime), spec.length)
			val train = Train(env, timetable)
			logger.debug { "Injecting test train ${train.name}: ${spec.inName} -> ${spec.outName} at t=$now" }
			onTrainCreated(train)
			placeTrain(train)
			trains.add(train)
		}

		override fun placeTrain(train: Train) {
			unapprowedTrains.addLast(train)
			trainsEnteredCount++
			blockTransitionsByTrain[train.name] = 0
		}

		override suspend fun interLoopSleep() {
			// Poll at 1s granularity (matches ShuntingLoop polling cadence).
			hold(1.0)
		}
	}

	override suspend fun startAction() {
		env.addReportTypes(*ENABLED_REPORT_TYPES)
		Process.activate(generator)
	}

	override suspend fun iteration() {
		// Clean up terminated trains and count exits.
		val iter: MutableIterator<Train> = approwedTrains.iterator()
		while (iter.hasNext()) {
			val t: Train = iter.next()
			if (t.terminated()) {
				iter.remove()
				trainsExitedCount++
				logger.debug { "Test train ${t.name} exited (total exits=$trainsExitedCount)" }
			}
		}

		// Approve queued trains up to MAX_TRAINS.
		approveTrains()
		if (approwedTrains.size > maxConcurrentTrainsCount) {
			maxConcurrentTrainsCount = approwedTrains.size
		}

		// Record each active train as "progressing through a block" per tick.
		// Cheap proxy so that tests can assert motion without log parsing.
		// KMP-safe increment: Map.merge() is a JVM-only default method.
		for (t in approwedTrains) {
			blockTransitionsByTrain[t.name] = (blockTransitionsByTrain[t.name] ?: 0) + 1
		}

		// Cadence aligned with ShuntingLoop.
		hold(1.0)
	}

	private fun approveTrains() {
		while (approwedTrains.size < MAX_TRAINS && unapprowedTrains.isNotEmpty()) {
			val train: Train = requireSimulationNotNull(unapprowedTrains.removeFirstOrNull())
			approwedTrains.add(train)
			activate(train)
		}
	}

	override suspend fun interLoopSleep() {
		if (time() >= endTime) {
			generator.terminate()
			env.stop()
			return
		}
		hold(1.0)
	}

	// --- Test-observability surface (mirrors #365 on ShuntingLoop) ---

	fun getTrainsEntered(): Int = trainsEnteredCount

	fun getTrainsExited(): Int = trainsExitedCount

	fun getMaxConcurrentTrains(): Int = maxConcurrentTrainsCount

	fun getBlockTransitions(trainId: String): Int = blockTransitionsByTrain[trainId] ?: 0

	fun getAllBlockTransitions(): Map<String, Int> = blockTransitionsByTrain.toMap()
}
