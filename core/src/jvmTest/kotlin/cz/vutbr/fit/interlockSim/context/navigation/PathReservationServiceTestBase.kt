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
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.BeforeEach
import org.koin.test.inject

/**
 * Shared fixture for the PathReservationService test suite.
 *
 * ## Test Coverage
 *
 * The suite (one file per former `@Nested` group, Issue #1165) covers:
 *
 * - ✅ Successful reservation (all blocks FREE)
 * - ✅ Partial failure rollback (atomic guarantee)
 * - ✅ Conflict detection (block OCCUPIED)
 * - ✅ Conflict detection (block RESERVED by different train)
 * - ✅ Multiple train coordination
 * - ✅ Release path and re-reserve
 * - ✅ Idempotent operations
 * - ✅ No path exists (topology)
 * - ✅ All paths blocked
 * - ✅ Path availability check
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
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	protected lateinit var simulationContext: DefaultSimulationContext
	protected lateinit var environment: SimulationEnvironment
	protected lateinit var navigator: TopologyNavigator
	protected lateinit var registry: PathReservationRegistry
	protected lateinit var service: PathReservationService
	protected lateinit var inOut1: DynamicPathSeparator
	protected lateinit var inOut2: DynamicPathSeparator

	@BeforeEach
	fun setUp() {
		// Load vyhybna.xml from resources
		simulationContext = TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)

		environment = simulationContext

		// Get navigation services from the simulation context
		// (Services are scoped to the context, accessed via public API)
		service = simulationContext.getRoutingServices().getPathReservationService()

		// TopologyNavigator is internal to PathReservationService, but tests need it
		// Create it directly for test purposes (not from scope)
		navigator = simulationContext.scope.get()
		registry = simulationContext.scope.get()

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
}
