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
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.BlockIdentity
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
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
 * - [orphanedTailFromAnAbortIsReclaimableAndItsAspectsReturnToStop] pins invariant I3: after an
 *   abort, the RESERVED tail that the aborted merge left out of the PathInfo is still reclaimable
 *   by [RegistryPartialRouteReleaser] (the [OrphanReservationSweeper]'s releaser), and the
 *   semaphores that were cleared for it come back to STOP. That reclaimability is what makes
 *   "never throw, just keep `old`" safe on its own, without releasing anything at abort time.
 * - [cleanBaselineRunLogsNoMergeAbortWarning] is the inertness gate: a plain `vyhybna` run must
 *   never trip either new WARN.
 *
 * Logback's [ListAppender] is used for the WARN assertions because `:core`'s test source set has
 * logback only as a `runtimeOnly` dependency; `dispatcher-agent`'s has it on the compile
 * classpath.
 */
@DisplayName("Aborted PathInfo merge — the simulation thread survives it (Issue #834)")
@Tag("integration-test")
class MergeAbortSimSurvivalTest {
	private companion object {
		/** Logger of the class whose WARNs are under inspection. */
		const val REGISTRY_LOGGER = "cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry"

		/** Distinctive fragment of the duplicated-new-start abort WARN. */
		const val DUPLICATED_START_WARN = "duplicated new start"

		/** Distinctive fragment of the non-contiguous abort WARN. */
		const val NON_CONTIGUOUS_WARN = "non-contiguous merge"

		const val SIM_END_TIME = 180L
	}

	private val xmlContextFactory = XMLContextFactory()
	private val processFactory = DefaultSimulationProcessFactory()

	private lateinit var context: DefaultSimulationContext
	private lateinit var appender: ListAppender<ILoggingEvent>
	private lateinit var registryLogger: Logger

	@BeforeEach
	fun setUp() {
		startKoin { modules(dispatcherAgentTestModule) }
		context =
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = xmlContextFactory.createContext(stream) as EditingContext
				DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
			}
		registryLogger = LoggerFactory.getLogger(REGISTRY_LOGGER) as Logger
		appender = ListAppender<ILoggingEvent>().apply { start() }
		registryLogger.addAppender(appender)
	}

	@AfterEach
	fun tearDown() {
		registryLogger.detachAppender(appender)
		appender.stop()
		context.close()
		stopKoin()
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
	@DisplayName("the orphaned tail left by an abort is reclaimable and its aspects return to STOP")
	fun orphanedTailFromAnAbortIsReclaimableAndItsAspectsReturnToStop() {
		val trainId = "Train #1"
		val zA = separatorAt(14, 8)
		val doB1 = separatorAt(25, 8)
		val zB = separatorAt(27, 8)

		// Given: a held route zA → doB1 whose first block the train is standing on ...
		service().reservePath(trainId, zA, doB1)
		val head =
			registry().getBlocks(trainId).firstOrNull { zA in it.ends() }
				?: error("No reserved block bounded by zA")
		head.enter(mockk<TrackOccupant>(relaxed = true) { every { name } returns trainId })

		// ... and a second, non-contiguous request whose merge ABORTS (new.start = zA, but the
		// stored path already ends at doB1). Its blocks are reserved and doB1 is cleared into the
		// new section BEFORE the merge is even attempted, so this is exactly the orphaned-tail
		// shape an abort creates: RESERVED track behind a proceed aspect, outside the PathInfo.
		service().reservePath(trainId, zA, zB)
		assertThat(warningsContaining(NON_CONTIGUOUS_WARN)).isNotEmpty()
		assertThat(registry().getPathInfo(trainId)!!.target).isEqualTo(doB1) // PathInfo untouched

		val tail =
			registry().getBlocks(trainId).filter {
				it.getState() == TrackFacility.State.RESERVED && it.occupant == null
			}
		assertThat(tail).isNotEmpty()
		val tailIds = tail.map { BlockIdentity.stableBlockId(it) }

		// Guard against a vacuous G1 assertion: the abort must really have left proceed aspects
		// standing for track the train will never reach — that is the damage being repaired.
		val litInsideTail =
			tail
				.flatMap { it.ends().toList() }
				.filterIsInstance<DynamicRailSemaphore>()
				.distinct()
				.filter { it.signal.isAllowing() }
		assertThat(litInsideTail, "semaphores lit inside the orphaned tail").isNotEmpty()

		// When: the sweeper's releaser reclaims the un-travelled tail (arch doc §6 row 6).
		val releaser =
			RegistryPartialRouteReleaser(
				registry = registry(),
				pathReservationService = service()
			)
		val released = releaser.releaseUntravelledTail(trainId, tailIds)

		// Then: the tail really came free — the abort left nothing that pins it.
		assertThat(released, "released ids").isNotEmpty()
		released.forEach { id ->
			val block = tail.single { BlockIdentity.stableBlockId(it) == id }
			assertThat(block.getState(), "state of released block $id").isEqualTo(TrackFacility.State.FREE)
			assertThat(block.trainName != trainId, "released block $id no longer owned by $trainId").isTrue()
		}

		// And no proceed aspect outlives the reservation (G1).
		litInsideTail.forEach { semaphore ->
			assertThat(semaphore.signal.name, "aspect of ${semaphore.name} after tail release")
				.isEqualTo("STOP")
		}
	}

	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	@DisplayName("inertness: a clean vyhybna baseline run trips neither merge-abort WARN")
	fun cleanBaselineRunLogsNoMergeAbortWarning() {
		context.getInOuts()
		val loop = ShuntingLoop(context, SIM_END_TIME)
		wireSynchronousDispatcher(context, loop)
		context.setMainProcess(loop)
		context.run()

		// Sanity: the run really did dispatch, so "no WARN" is not "no work".
		assertThat(loop.getTrainsExited()).isGreaterThanOrEqualTo(1)

		assertThat(warningsContaining(DUPLICATED_START_WARN)).isEmpty()
		assertThat(warningsContaining(NON_CONTIGUOUS_WARN)).isEmpty()
	}
}
