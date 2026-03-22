package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.sim.SimpleTestProcess
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import cz.vutbr.fit.interlockSim.util.Point
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Native-compatible sanity tests for core domain objects.
 *
 * These tests use only commonMain code and kotlin.test — no JUnit5, no MockK,
 * no java.* imports. They run on both JVM (jvmTest via commonTest) and linuxX64.
 *
 * @since 2026-03-20 (KMP Step 4 — linuxX64 native test subset)
 */
class NativeSanityTest : KoinComponent {

    @BeforeTest
    fun setUpKoin() {
        startKoin { modules(commonCoreTestModule) }
    }

    @AfterTest
    fun tearDownKoin() {
        stopKoin()
    }

    @Test
    fun `DefaultEditingContext can be created with valid dimensions`() {
        DefaultEditingContext(30, 30).use { ctx ->
            assertThat(ctx.getRailWayNetGrid()).isNotNull()
            assertThat(ctx.getRailWayNetGrid().cols).isEqualTo(30)
            assertThat(ctx.getRailWayNetGrid().rows).isEqualTo(30)
        }
    }

    @Test
    fun `InOut can be placed in editing context`() {
        DefaultEditingContext(30, 30).use { ctx ->
            val inA = InOut("A", false, Cell.SpatialType.HORIZONTAL)
            ctx.putCell(Point(1, 1), inA)
            val found = ctx.getRailWayNetGrid().getCellAt(1, 1)
            assertThat(found).isEqualTo(inA)
        }
    }

    @Test
    fun `two InOuts can be connected by a SimpleTrackBlock`() {
        DefaultEditingContext(30, 30).use { ctx ->
            val inA = InOut("A", false, Cell.SpatialType.HORIZONTAL)
            val inB = InOut("B", true, Cell.SpatialType.HORIZONTAL)
            val track = SimpleTrackBlock(inA, inB, 100.0, 80.0)
            ctx.putCell(Point(1, 1), inA)
            ctx.putCell(Point(5, 5), inB)
            ctx.joinCells(Point(1, 1), Point(5, 5), track)
            assertThat(ctx.getInOuts()).hasSize(2)
        }
    }

    @Test
    fun `AutoNameGenerator generates non-empty names`() {
        DefaultEditingContext(30, 30).use { ctx ->
            val name = AutoNameGenerator.generateName(InOut::class, ctx)
            assertThat(name).isNotEmpty()
        }
    }

    @Test
    fun `SimpleTestProcess compiles on native target`() {
        // Verifies SimpleTestProcess.kt links on linuxX64 by referencing its class.
        assertThat(SimpleTestProcess::class.simpleName).isEqualTo("SimpleTestProcess")
    }

    @Test
    fun `DefaultSimulationContext scope opens and closes without error`() {
        val processFactory = get<SimulationProcessFactory>()
        val editingCtx = CommonTestFixtures.parseEditingContext(NetworkResources.LINEAR_TRACK_XML)
        DefaultSimulationContext.fromEditingContext(editingCtx, processFactory).use { simCtx ->
            assertThat(simCtx).isNotNull()
            assertThat(simCtx.scope).isNotNull()
        }
    }

    @Test
    fun `TopologyNavigator resolves from simulation context`() {
        val processFactory = get<SimulationProcessFactory>()
        val editingCtx = CommonTestFixtures.parseEditingContext(NetworkResources.LINEAR_TRACK_XML)
        DefaultSimulationContext.fromEditingContext(editingCtx, processFactory).use { simCtx ->
            val navigator: TopologyNavigator = simCtx.getTopologyNavigator()
            assertThat(navigator).isNotNull()
        }
    }
}
