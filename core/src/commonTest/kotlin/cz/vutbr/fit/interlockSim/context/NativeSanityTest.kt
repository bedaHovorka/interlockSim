package cz.vutbr.fit.interlockSim.context

import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.sim.SimpleTestProcess
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
class NativeSanityTest {

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
        val ctx = DefaultEditingContext(30, 30)
        try {
            assertNotNull(ctx.getRailWayNetGrid())
            assertEquals(30, ctx.getRailWayNetGrid().getCols())
            assertEquals(30, ctx.getRailWayNetGrid().getRows())
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `InOut can be placed in editing context`() {
        val ctx = DefaultEditingContext(30, 30)
        try {
            val inA = InOut("A", false, Cell.SpatialType.HORIZONTAL)
            ctx.putCell(Point(1, 1), inA)
            val found = ctx.getRailWayNetGrid().getCellAt(1, 1)
            assertEquals(inA, found)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `two InOuts can be connected by a SimpleTrackBlock`() {
        val ctx = DefaultEditingContext(30, 30)
        try {
            val inA = InOut("A", false, Cell.SpatialType.HORIZONTAL)
            val inB = InOut("B", true, Cell.SpatialType.HORIZONTAL)
            val track = SimpleTrackBlock(inA, inB, 100.0, 80.0)
            ctx.putCell(Point(1, 1), inA)
            ctx.putCell(Point(5, 5), inB)
            ctx.joinCells(Point(1, 1), Point(5, 5), track)
            val inOuts = ctx.getInOuts()
            assertEquals(2, inOuts.size)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `AutoNameGenerator generates non-empty names`() {
        val ctx = DefaultEditingContext(30, 30)
        try {
            val name = AutoNameGenerator.generateName(InOut::class, ctx)
            assertTrue(name.isNotEmpty(), "Generated name should not be empty")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `SimpleTestProcess compiles on native target`() {
        // Verifies SimpleTestProcess.kt links on linuxX64 by referencing its class.
        // Full simulation is not run here (requires a kDisco environment).
        assertEquals("SimpleTestProcess", SimpleTestProcess::class.simpleName)
    }
}
