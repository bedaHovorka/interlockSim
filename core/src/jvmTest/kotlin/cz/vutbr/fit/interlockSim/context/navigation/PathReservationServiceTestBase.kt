/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.testutil.withLogOf
import org.junit.jupiter.api.BeforeEach
import org.koin.test.inject

/**
 * Shared fixture for the PathReservationService test suite.
 *
 * ## Test Coverage
 *
 * One file per former `@Nested` group (Issue #1165); each group class name and its own KDoc
 * state what that group covers, so this base does not restate the suite's coverage.
 *
 * ## Test Data
 *
 * Uses vyhybna.xml network:
 * - inOut1 → TrackBlock → RailSwitch → TrackBlock → inOut2
 * - Simple linear network with switch in middle
 *
 * @since Issue #294 (Phase 2 of Issue #292)
 * @since Issue #1165 (extracted from PathReservationServiceTest)
 */
abstract class PathReservationServiceTestBase : KoinTestBase() {
	/**
	 * The simulation context the concrete test class runs on. Default: vyhybna.xml (the
	 * bundled example). Override to run on another fixture network — every lookup and helper
	 * below (inOut1/inOut2, findSemaphoreByName, findSwitchByName, inOutNamed, blockBetween,
	 * occupy, assertPathContainsSeparators) reads from this one context and works for any
	 * loaded network, so a new fixture is an override, not a fork of this base (PR #1194
	 * review round).
	 */
	protected open fun loadTestContext(): DefaultSimulationContext = loadVyhybnaContext()

	/**
	 * How many InOuts [setUp] asserts the [loadTestContext] network has. Default 2 (vyhybna.xml and
	 * the other two-ended fixtures); a fixture with more endpoints overrides it next to
	 * [loadTestContext] instead of forking this base (Issue #1394). [inOut1]/[inOut2] are the first
	 * two of them in the context's order, so the count must be at least 2.
	 */
	protected open val expectedInOutCount: Int = 2

	protected val editingContextFactory: JvmEditingContextFactory by inject()
	protected val simulationContextFactory: SimulationContextFactory by inject()

	protected lateinit var simulationContext: DefaultSimulationContext
	protected lateinit var environment: SimulationEnvironment
	protected lateinit var navigator: TopologyNavigator
	protected lateinit var registry: PathReservationRegistry

	/** Resolved eagerly from [simulationContext]'s scope in [setUp] (a cheap scope lookup). */
	protected lateinit var pathInfoBuilder: PathInfoBuilder

	/** Resolved eagerly from [simulationContext]'s scope in [setUp] (a cheap scope lookup). */
	protected lateinit var routeFinder: RouteFinder

	protected lateinit var service: PathReservationService
	protected lateinit var inOut1: DynamicPathSeparator
	protected lateinit var inOut2: DynamicPathSeparator

	@BeforeEach
	fun setUp() {
		// Load the test class's network from resources
		simulationContext = loadTestContext().tracked()

		environment = simulationContext

		// Get navigation services from the simulation context
		// (Services are scoped to the context, accessed via public API)
		service = simulationContext.getRoutingServices().getPathReservationService()

		// TopologyNavigator is internal to PathReservationService, but tests need it
		// Create it directly for test purposes (not from scope)
		navigator = simulationContext.scope.get()
		registry = simulationContext.scope.get()
		pathInfoBuilder = simulationContext.scope.get()
		routeFinder = simulationContext.scope.get()

		// Get InOut elements
		val inOuts = simulationContext.getInOuts()
		assertThat(inOuts.size).isEqualTo(expectedInOutCount)

		// Convert Java List to Kotlin List and get by index
		val inOutsList = inOuts.toList()
		inOut1 = simulationContext.toDynamic(inOutsList[0])
		inOut2 = simulationContext.toDynamic(inOutsList[1])

		assertThat(inOut1).isNotNull()
		assertThat(inOut2).isNotNull()
	}

	/**
	 * The semaphore named [name] in the loaded grid.
	 *
	 * Eight of the suite's group classes carried a private copy of this grid sweep
	 * (Issue #1183, SP6.1 follow-up).
	 *
	 * @throws IllegalStateException when the grid holds no semaphore of that name
	 */
	protected fun findSemaphoreByName(name: String): DynamicRailSemaphore =
		simulationContext.cellsOfType<DynamicRailSemaphore>().firstOrNull { it.name == name }
			?: throw IllegalStateException("Semaphore $name not found in grid")

	/**
	 * The rail switch named [name] in the loaded grid, the switch twin of [findSemaphoreByName]
	 * ([DynamicRailSwitch] is not a [cz.vutbr.fit.interlockSim.objects.cells.NodeCell], so the
	 * generic by-name lookups do not cover it).
	 *
	 * @throws IllegalStateException when the grid holds no switch of that name
	 */
	protected fun findSwitchByName(name: String): DynamicRailSwitch =
		simulationContext.cellsOfType<DynamicRailSwitch>().firstOrNull { it.name == name }
			?: throw IllegalStateException("Switch $name not found in grid")

	/**
	 * The dynamic InOut named [name].
	 *
	 * Reads the context's `getInOuts()` and maps through `toDynamic`, so the returned wrapper is
	 * the very instance the reservation services use — the grid sweep would be equivalent here
	 * (`toDynamic` caches one wrapper per static separator), but this is the form the suite
	 * carried and the one that cannot depend on grid order.
	 */
	protected fun inOutNamed(name: String): DynamicInOut =
		simulationContext
			.getInOuts()
			.map { simulationContext.toDynamic(it) }
			.filterIsInstance<DynamicInOut>()
			.single { it.name == name }

	/** Name of a separator, whatever concrete dynamic cell type it is; `null` if unnamed. */
	protected fun separatorNameOf(separator: PathSeparator): String? =
		when (separator) {
			is DynamicRailSemaphore -> separator.name
			is DynamicRailSwitch -> separator.name
			is DynamicInOut -> separator.name
			else -> null
		}

	/**
	 * The single block whose two ends are the separators named [first] and [second].
	 * `vyhybna.xml` blocks carry no XML name of their own, so they are addressed by
	 * their endpoints (the same identity `ShuntingLoop` labels `kA`/`kB`/`k1`/`k2`).
	 */
	protected fun blockBetween(
		first: String,
		second: String
	): DynamicTrackBlock =
		simulationContext
			.getGraph()
			.values()
			.filterIsInstance<DynamicTrackBlock>()
			.firstOrNull { block ->
				block.ends().mapNotNull { separatorNameOf(it) }.toSet() == setOf(first, second)
			} ?: throw IllegalStateException("No block found between $first and $second")

	/**
	 * `reservePath` over the switch-blind candidates of [start] -> [target], through the internal
	 * caller-supplied-paths overload. Since Issue #1385 the public `reservePath` enumerates only
	 * switch-legal routes, so a candidate that reverses through a switch never reaches the screens,
	 * the switch configurator or the exhausted-candidate classification. Tests whose subject is that
	 * downstream machinery feed it the old phantom candidates here; tests of what a request returns
	 * use the public overload. Runs no reservation expiry, unlike the public overload.
	 *
	 * For the vyhybna and Praha pairs involved, these guards are now unreachable through the public API:
	 * the Issue #1076 null-configuration ownership guard, the Issue #742 reversal / G4 rollback path, the
	 * Issue #1066 divergence screen and the Step 1.4 mixed fold (Issue #1378). They stay as defence in
	 * depth, and so do the tests that reach them here: do not delete either as dead code.
	 */
	protected fun reservePathOverSwitchBlindCandidates(
		trainId: String,
		start: DynamicPathSeparator,
		target: DynamicPathSeparator
	): PathReservationService.ReservationResult =
		(service as DefaultPathReservationService).reservePath(
			trainId,
			start,
			target,
			DEFAULT_MAX_PATH_DEPTH,
			lazyOf(navigator.findAllTopologicalPaths(start, target, DEFAULT_MAX_PATH_DEPTH))
		)

	/**
	 * A [DefaultPathReservationService] over [navigator] on the loaded context — the construction
	 * the Praha suite's `serviceOver` (`SignalTargetSwitchConstrainedPrahaTest`) and the de-dup
	 * suite's `newService` (`ReservePathToAnyDedupTest`) delegate to. [nextTargetsOverride] replaces
	 * the target list of `reservePathToAnyNextSemaphore`, the Issue #1390 test seam (`null` in
	 * production).
	 */
	protected fun serviceOf(
		navigator: TopologyNavigator,
		nextTargetsOverride: ((DynamicPathSeparator, TrackSection) -> List<DynamicPathSeparator>)? = null
	): DefaultPathReservationService =
		DefaultPathReservationService(
			navigator,
			simulationContext,
			registry,
			pathInfoBuilder,
			routeFinder,
			nextTargetsOverride = nextTargetsOverride
		)

	/**
	 * One switch-constrained enumeration [RecordingNavigator] recorded: the `(start, target)` pair
	 * and the candidate list the search handed back for it. A proper data class, so a consumer
	 * filters the records by name instead of by nested-pair position.
	 */
	protected data class RecordedSearch(
		val start: PathSeparator,
		val target: PathSeparator,
		val answer: List<List<TrackSection>>
	)

	/**
	 * A [TopologyNavigator] that delegates everything to [real] and records the enumerations the
	 * reservation machinery runs through it (Issue #1385) — one double for the three suites whose
	 * private counting doubles it replaces. Every `findAllSwitchConstrainedPaths` call is noted
	 * once, as the full record with the handed-back answer ([switchConstrainedAnswers],
	 * [RecordedSearch]); the probe count ([evaluations], one per availability evaluation) and the
	 * per-pair call count ([calls]) are read-only views computed from that record, so they cannot
	 * disagree. [reset] clears the record and [topologicalCalls] together to recount from the
	 * next call (Issue #1405). A switch-blind [findAllTopologicalPaths] call is counted alone in
	 * [topologicalCalls], the seam the public `reservePath` must never run since Issue #1385.
	 *
	 * On top of recording, a target in [noRouteTo] is always answered with no candidates and a
	 * target in [stubbedSwitchConstrained] with the mapped list, so a test can steer a request to
	 * targets real discovery no longer offers (the permanent-verdict pins); every other target goes
	 * to [real].
	 */
	protected class RecordingNavigator(
		private val real: TopologyNavigator,
		private val noRouteTo: Set<PathSeparator> = emptySet(),
		private val stubbedSwitchConstrained: Map<PathSeparator, List<List<TrackSection>>> = emptyMap()
	) : TopologyNavigator by real {
		/** The switch-blind [findAllTopologicalPaths] calls, counted alone. */
		var topologicalCalls = 0
			private set

		/** The switch-constrained searches with their answers, in call order: the only stored record. */
		val switchConstrainedAnswers = mutableListOf<RecordedSearch>()

		/** The switch-constrained searches, one count per call (the availability-probe counter); derived. */
		val evaluations: Int
			get() = switchConstrainedAnswers.size

		/** The switch-constrained searches, per `(start, target)` pair; derived. */
		val calls: Map<Pair<PathSeparator, PathSeparator>, Int>
			get() = switchConstrainedAnswers.groupingBy { it.start to it.target }.eachCount()

		/** Forget every recorded search and the topological count, so the next call is counted from zero. */
		fun reset() {
			switchConstrainedAnswers.clear()
			topologicalCalls = 0
		}

		override fun findAllTopologicalPaths(
			start: PathSeparator,
			target: PathSeparator,
			maxDepth: Int
		): List<List<TrackSection>> {
			topologicalCalls++
			return real.findAllTopologicalPaths(start, target, maxDepth)
		}

		override fun findAllSwitchConstrainedPaths(
			start: PathSeparator,
			target: PathSeparator,
			maxDepth: Int
		): List<List<TrackSection>> {
			val answer =
				when (target) {
					in noRouteTo -> emptyList()
					in stubbedSwitchConstrained -> stubbedSwitchConstrained.getValue(target)
					else -> real.findAllSwitchConstrainedPaths(start, target, maxDepth)
				}
			switchConstrainedAnswers += RecordedSearch(start, target, answer)
			return answer
		}
	}

	/**
	 * Put [trainId] physically on [block] without touching the registry — the state a
	 * train admitted before any route was granted is in.
	 */
	protected fun occupy(
		block: DynamicTrackBlock,
		trainId: String
	) {
		block.setUpPath(block.ends().first() as DynamicPathSeparator, trainId)
		block.enter(
			FakeTrackOccupant(trainId)
		)
	}

	/** Assert that the endpoints of [blocks] include every separator in [separatorNames]. */
	protected fun assertPathContainsSeparators(
		blocks: List<DynamicTrackBlock>,
		vararg separatorNames: String
	) {
		val found =
			blocks
				.flatMap { block -> block.ends().mapNotNull { separatorNameOf(it) } }
				.filter { it.isNotEmpty() }
				.toSet()

		separatorNames.forEach { expectedName ->
			assertThat(found, name = "separators bounding the reserved blocks").contains(expectedName)
		}
	}

	/**
	 * Assert that every block in [blocks] is FREE and unowned in the registry — the end state a
	 * whole-route release must reach; three release pins share it.
	 */
	protected fun assertBlocksFreeAndUnowned(blocks: List<DynamicTrackBlock>) {
		blocks.forEach { block ->
			assertThat(block.getState(), "state of $block").isEqualTo(TrackFacility.State.FREE)
			assertThat(registry.getOwner(block), "owner of $block").isNull()
		}
	}

	/** The aspect of every semaphore of the network, by name. */
	protected fun semaphoreAspects(): Map<String, Signal> =
		simulationContext.cellsOfType<DynamicRailSemaphore>().associate { it.name to it.signal }

	/**
	 * Runs [body] with an appender on the [RouteReleaser] logger, which `logback-test.xml` keeps at
	 * DEBUG (Issue #1242: its refused-release line is INFO once per train, then DEBUG).
	 */
	protected fun withReleaserLog(body: (ListAppender<ILoggingEvent>) -> Unit) =
		withLogOf(RouteReleaser::class.java.name, body)

	/**
	 * The [RouteReleaser]'s `cannot be trimmed` lines of one kind about [trainId] at [level] (Issue
	 * #1242): [prefix] is [RELEASER_REFUSED] or [RELEASER_DEFERRED], so a refusal never counts as a
	 * deferral or the other way round.
	 */
	protected fun releaserLines(
		appender: ListAppender<ILoggingEvent>,
		level: Level,
		prefix: String,
		trainId: String = "train1"
	): List<ILoggingEvent> =
		appender.list.filter {
			it.level == level &&
				it.formattedMessage.startsWith("$prefix'$trainId'") &&
				it.formattedMessage.contains("cannot be trimmed")
		}

	/**
	 * The [RouteReleaser]'s INFO lines of a detailed release that went ahead for [trainId] (Issue
	 * #1254), in emission order: the [RELEASER_RELEASED] lines only, never a refusal or a deferral.
	 */
	protected fun releasedLines(
		appender: ListAppender<ILoggingEvent>,
		trainId: String = "train1"
	): List<String> =
		appender.list
			.filter { it.level == Level.INFO && it.formattedMessage.startsWith("$RELEASER_RELEASED'$trainId'") }
			.map { it.formattedMessage }

	protected companion object {
		/** The line of a release that freed blocks and kept others (Issue #1254), up to the train name. */
		const val RELEASER_RELEASED = "releasePath: released for "

		/** The releaser's refusal line (Issue #1242), up to the train name. */
		const val RELEASER_REFUSED = "releasePath: refused for "

		/** The releaser's approach-lock deferral line (Issue #1050, #1242), up to the train name. */
		const val RELEASER_DEFERRED = "releasePath: approach lock for "
	}
}
