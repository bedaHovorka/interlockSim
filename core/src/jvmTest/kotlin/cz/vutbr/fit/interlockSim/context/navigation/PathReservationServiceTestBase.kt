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
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
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

	protected val editingContextFactory: JvmEditingContextFactory by inject()
	protected val simulationContextFactory: SimulationContextFactory by inject()

	protected lateinit var simulationContext: DefaultSimulationContext
	protected lateinit var environment: SimulationEnvironment
	protected lateinit var navigator: TopologyNavigator
	protected lateinit var registry: PathReservationRegistry
	protected lateinit var pathInfoBuilder: PathInfoBuilder
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
		assertThat(inOuts.size).isEqualTo(2)

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
	protected fun inOutNamed(name: String): DynamicPathSeparator =
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
}
