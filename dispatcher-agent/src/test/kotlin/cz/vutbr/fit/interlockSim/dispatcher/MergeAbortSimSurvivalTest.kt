/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.runShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #834 (SP2c.11): an aborted PathInfo merge must not take the simulation with it.
 *
 * ## The failure this pins
 *
 * `PathReservationRegistry.mergePathInfo` used to throw `IllegalStateException` when the new
 * path's start appeared more than once in it. It is reached from
 * `DefaultPathReservationService.reservePath` **Step 2i**, i.e. on the kDisco simulation thread,
 * after blocks are reserved and a START signal is already cleared. Nothing on that path catches
 * it: [DispatchDecisionApplier.onControlStep] catches only `IllegalArgumentException`, and so
 * does `wireSynchronousDispatcher`'s control-step listener. The exception therefore killed the
 * simulation thread — while the surrounding run still wrote a well-formed JSON result. A
 * fabricated measurement is the one outcome #834's *measured* acceptance criterion cannot
 * tolerate, which is why this regression is tested end-to-end rather than only at unit level.
 *
 * ## Test shapes
 *
 * - [pathologicalMergeOnSimThreadDoesNotKillTheRun] injects, on the sim thread and inside a
 *   control step, exactly the `registerPathInfo` call Step 2i makes — with the pathological
 *   duplicated-start `PathInfo` — and asserts the run keeps stepping and keeps dispatching
 *   afterwards. The injection is done through the registry rather than through a contrived
 *   topology because what is under test is the *thread survival*, not the route that produces
 *   the shape.
 * - [mergeAbortedReservePathLeavesNoOrphanedTail] pins the Issue #904 update to invariant I3:
 *   `reservePath` itself now releases exactly what an aborted candidate acquired -- blocks,
 *   switches, and the signal it cleared, driven back to STOP -- before the abort can ever
 *   surface. There is no orphaned RESERVED tail left for `OrphanReservationSweeper`
 *   (`RegistryPartialRouteReleaser`) to reclaim later; the old "never throw, just keep `old`,
 *   rely on the sweeper" trade-off no longer applies to this exit.
 * - [cleanBaselineRunLogsNoMergeAbortWarning] is the inertness gate: a plain `vyhybna` run must
 *   never trip either new WARN.
 *
 * Logback's [ListAppender] is used for the WARN assertions because `:core`'s test source set has
 * logback only as a `runtimeOnly` dependency; `dispatcher-agent`'s has it on the compile
 * classpath.
 */
@DisplayName("Aborted PathInfo merge — the simulation thread survives it (Issue #834)")
@Tag("integration-test")
class MergeAbortSimSurvivalTest : DispatcherKoinTestBase() {
	private companion object {
		/** Logger of the class whose WARNs are under inspection. */
		const val REGISTRY_LOGGER = "cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry"

		/** Distinctive fragment of the duplicated-new-start abort WARN. */
		const val DUPLICATED_START_WARN = "duplicated new start"

		/** Distinctive fragment of the non-contiguous abort WARN. */
		const val NON_CONTIGUOUS_WARN = "non-contiguous merge"

		const val SIM_END_TIME = 180L
	}

	private lateinit var context: DefaultSimulationContext
	private lateinit var appender: ListAppender<ILoggingEvent>
	private lateinit var registryLogger: Logger

	@BeforeEach
	fun setUp() {
		context = TestFixtures.newShuntingSimulationContext().tracked()
		registryLogger = LoggerFactory.getLogger(REGISTRY_LOGGER) as Logger
		appender = ListAppender<ILoggingEvent>().apply { start() }
		registryLogger.addAppender(appender)
	}

	@AfterEach
	fun tearDown() {
		registryLogger.detachAppender(appender)
		appender.stop()
	}

	private fun registry(): PathReservationRegistry = context.scope.get<PathReservationRegistry>()

	private fun service(): PathReservationService = context.getRoutingServices().getPathReservationService()

	private fun separatorAt(
		x: Int,
		y: Int
	): DynamicPathSeparator {
		val cell = context.getRailWayNetGrid()[Point(x, y)]
		val separator = cell as? PathSeparator ?: error("No separator at ($x, $y): $cell")
		return context.toDynamic(separator)
	}

	/** WARN messages emitted by the registry that contain [fragment]. */
	private fun warningsContaining(fragment: String): List<String> =
		appender.list
			.filter { it.level == Level.WARN }
			.map { it.formattedMessage }
			.filter { it.contains(fragment) }

	/** Any [TrackSection] on a topological path between [from] and [to]. */
	private fun someSectionBetween(
		from: DynamicPathSeparator,
		to: DynamicPathSeparator
	): TrackSection =
		context
			.getRoutingServices()
			.getTopologyNavigator()
			.findAllTopologicalPaths(from, to)
			.firstOrNull()
			?.filterIsInstance<TrackSection>()
			?.firstOrNull()
			?: error("No track section between $from and $to")

	/** Single-separator `PathInfo` — a legal first registration that performs no merge. */
	private fun seedPathInfo(separator: DynamicPathSeparator): PathInfo =
		PathInfo(
			start = separator,
			target = separator,
			reservedPath = ArrayPath(context).apply { add(separator) },
			entryDirections = emptyMap()
		)

	/**
	 * The pathological `PathInfo` whose `start` occurs twice in its own reserved path — the shape
	 * `requireValidState(occurrences == 1)` used to throw on. Built directly because no vyhybna
	 * route produces it; the defect under test is what happens *after* such a shape reaches
	 * `registerPathInfo`, not how it gets there.
	 */
	private fun duplicatedStartPathInfo(
		separator: DynamicPathSeparator,
		section: TrackSection
	): PathInfo {
		val path = ArrayPath(context)
		path.add(separator)
		path.add(section)
		path.add(separator) // second occurrence — the pathological part
		return PathInfo(
			start = separator,
			target = separator,
			reservedPath = path,
			entryDirections = emptyMap()
		)
	}

	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	@DisplayName("a pathological merge on the sim thread does not kill the run")
	fun pathologicalMergeOnSimThreadDoesNotKillTheRun() {
		// Initialise the dynamic wrapper map before constructing the loop (see ExampleRegistry).
		context.getInOuts()
		val loop = ShuntingLoop(context, SIM_END_TIME)
		wireSynchronousDispatcher(context, loop)

		val zA = separatorAt(14, 8)
		val doA1 = separatorAt(16, 8)
		val probeTrain = "MergeAbortProbe"
		// Resolved before the run so the injection itself stays a pure registry call.
		val section = someSectionBetween(doA1, zA)

		val controlSteps = AtomicInteger(0)
		val stepAtInjection = AtomicInteger(-1)
		val injected = AtomicInteger(0)

		val wired = loop.controlStepListener ?: error("wireSynchronousDispatcher did not install a listener")
		loop.controlStepListener =
			ControlStepListener {
				val step = controlSteps.incrementAndGet()
				// Inject once, a few steps in, so there is a meaningful "after" to observe.
				if (step == 5 && injected.compareAndSet(0, 1)) {
					stepAtInjection.set(step)
					// Give the probe a first PathInfo (no merge), then feed it the pathological
					// one — precisely the `registry.registerPathInfo(trainId, pathInfo)` statement
					// reservePath Step 2i executes, on precisely this thread. The probe reserves
					// no blocks: registerPathInfo is a pure data-structure operation (I1), and a
					// block-taking probe would starve the real trains this run must still complete.
					// The pathological path starts at doA1, i.e. AT the probe's own front, so the
					// contiguity guard passes and the duplicated-start guard is what fires.
					registry().registerPathInfo(probeTrain, seedPathInfo(doA1))
					registry().registerPathInfo(probeTrain, duplicatedStartPathInfo(doA1, section))
				}
				wired.onControlStep()
			}

		context.setMainProcess(loop)
		context.run()

		// The injection actually happened — otherwise everything below is vacuous.
		assertThat(injected.get()).isEqualTo(1)

		// The simulation kept stepping after the abort. Before the fix the IllegalStateException
		// propagated out of onControlStep and the sim thread died at step 5.
		assertThat(controlSteps.get()).isGreaterThan(stepAtInjection.get())

		// And it kept *dispatching*, not merely ticking: trains still complete journeys.
		assertThat(loop.getTrainsExited()).isGreaterThanOrEqualTo(1)

		// The abort was reported, once, as a WARN — not swallowed silently.
		assertThat(warningsContaining(DUPLICATED_START_WARN)).isNotEmpty()

		// The probe's stored PathInfo is the pre-merge one: target still doA1, never the
		// pathological start-equals-target shape (invariant I2).
		val probePathInfo = registry().getPathInfo(probeTrain)
		assertThat(probePathInfo).isNotNull()
		assertThat(probePathInfo!!.target).isEqualTo(doA1)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName(
		"a merge-aborted reservePath call leaves no orphaned tail -- nothing for the sweeper to reclaim (Issue #904)"
	)
	fun mergeAbortedReservePathLeavesNoOrphanedTail() {
		val trainId = "Train #904 probe"
		val zA = separatorAt(14, 8)
		val doA1 = separatorAt(16, 8)
		val zB = separatorAt(27, 8)

		// Given: a PathInfo with NO real reservation behind it -- the FIRST-ever
		// registerPathInfo call for this trainId, so it stores directly (no merge, no
		// blocks/switches/signal acquired). Its target (zB, unrelated to the real reservation
		// below) will not match a genuinely reserved candidate's start, the same "probe train,
		// no footprint" shape [pathologicalMergeOnSimThreadDoesNotKillTheRun] already uses.
		registry().registerPathInfo(trainId, seedPathInfo(zB))

		// When: a REAL reservation. The train's footprint is empty (no blocks/switches ever
		// registered for it), so Step 0's contiguity check passes vacuously for any start
		// (Issue #893's documented exemption) -- this genuinely reserves blocks, locks vA, and
		// clears zA's signal (Steps 2d-2h) before Step 2i's merge sees new.start (zA) !=
		// old.target (zB, from the seed) and aborts. This reproduces the genuine abort shape
		// Issue #904's root-cause fix does NOT eliminate -- unlike the ordinary "extend using
		// the original start" pattern (Issue #911's shape), which now merges cleanly instead of
		// aborting, so the corruption must be set up this way rather than via two real
		// `reservePath` calls on the same train.
		service().reservePath(trainId, zA, doA1)
		assertThat(warningsContaining(NON_CONTIGUOUS_WARN)).isNotEmpty()
		assertThat(registry().getPathInfo(trainId)!!.target).isEqualTo(zB) // PathInfo untouched

		// Then: the train's ownership is back to exactly what it was before the aborted attempt
		// -- there was nothing before, so there must be nothing after either.
		assertThat(registry().getBlocks(trainId))
			.withMessage("a merge-abort must release exactly the blocks THIS attempt acquired")
			.isEmpty()
		assertThat(registry().getSwitches(trainId))
			.withMessage("a merge-abort must release any switches THIS attempt locked")
			.isEmpty()
		assertThat((zA as DynamicRailSemaphore).signal.isAllowing())
			.withMessage("a merge-abort must not leave a proceed aspect standing over an abandoned candidate")
			.isFalse()
	}

	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	@DisplayName("inertness: a clean vyhybna baseline run trips neither merge-abort WARN")
	fun cleanBaselineRunLogsNoMergeAbortWarning() {
		val loop = runShuntingLoop(context, SIM_END_TIME)

		// Sanity: the run really did dispatch, so "no WARN" is not "no work".
		assertThat(loop.getTrainsExited()).isGreaterThanOrEqualTo(1)

		assertThat(warningsContaining(DUPLICATED_START_WARN)).isEmpty()
		assertThat(warningsContaining(NON_CONTIGUOUS_WARN)).isEmpty()
	}
}
