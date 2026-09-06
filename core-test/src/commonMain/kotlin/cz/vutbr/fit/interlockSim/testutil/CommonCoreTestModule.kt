package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.CommonSimulationContextFactory
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Core test module for native-compatible (commonTest) tests.
 *
 * Uses programmatic context construction (no XML, no java.* imports).
 * Safe to use on all KMP targets including linuxX64.
 *
 * @since 2026-03-20 (KMP Step 4 — split commonTest for linuxX64)
 */
/**
 * Minimal EditingContextFactory for commonTest: only supports createEmptyContext().
 */
private class CommonTestEditingContextFactory : EditingContextFactory {
	override fun createEmptyContext(): EditingContext = DefaultEditingContext(100, 100)
}

/**
 * Minimal CommonSimulationContextFactory for commonTest: builds contexts programmatically.
 */
private class CommonTestSimulationContextFactory(
	private val processFactory: SimulationProcessFactory,
	private val editingContextFactory: EditingContextFactory,
) : CommonSimulationContextFactory {
	override fun createContext(editingContext: EditingContext): SimulationContext =
		DefaultSimulationContext.fromEditingContext(editingContext, processFactory)

	override fun createEmptyContext(): SimulationContext =
		editingContextFactory.createEmptyContext().use { editingContext ->
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}
}

val commonCoreTestModule: Module =
	module {
		// Provide EditingContextFactory (common-only, no XML/file I/O)
		single<EditingContextFactory> { CommonTestEditingContextFactory() }

		// Provide CommonSimulationContextFactory (common-only, no XML/file I/O)
		single<CommonSimulationContextFactory> { CommonTestSimulationContextFactory(get(), get()) }

		// Default editing context factory: creates a minimal linear-track context
		factory<DefaultEditingContext> {
			val ctx = DefaultEditingContext(30, 30)
			val inA = InOut("A", false, Cell.SpatialType.HORIZONTAL)
			val inB = InOut("B", true, Cell.SpatialType.HORIZONTAL)
			val track = SimpleTrackBlock(inA, inB, 100.0, 80.0)
			ctx.putCell(Point(1, 1), inA)
			ctx.putCell(Point(5, 5), inB)
			ctx.joinCells(Point(1, 1), Point(5, 5), track)
			ctx
		}

		factory<DefaultSimulationContext> {
			val processFactory = get<SimulationProcessFactory>()
			get<DefaultEditingContext>().use { editingCtx ->
				DefaultSimulationContext.fromEditingContext(editingCtx, processFactory)
			}
		}

		// Bindings shared with the other test module flavor (scope bindings plus the two
		// shared non-scope bindings) live in sharedSimulationTestScopesModule (Issue #1029).
		includes(sharedSimulationTestScopesModule)
	}
