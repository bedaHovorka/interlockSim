package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.DefaultTopologyNavigator
import cz.vutbr.fit.interlockSim.context.navigation.DefaultTrainNavigationService
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
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
val commonCoreTestModule: Module =
    module {
        single<SimulationProcessFactory> { DefaultSimulationProcessFactory() }

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
            val editingCtx = get<DefaultEditingContext>()
            val processFactory = get<SimulationProcessFactory>()
            DefaultSimulationContext.fromEditingContext(editingCtx, processFactory)
        }

        // Define editingScope for per-context lifecycle management
        scope<DefaultEditingContext> {
            scoped<TopologyNavigator> {
                val context =
                    getSource<DefaultEditingContext>()
                        ?: throw IllegalStateException("DefaultEditingContext source not found in scope")
                DefaultTopologyNavigator(context)
            }
        }

        // Define simulationScope for per-context lifecycle management
        scope<DefaultSimulationContext> {
            scoped<TopologyNavigator> {
                val context =
                    getSource<DefaultSimulationContext>()
                        ?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
                DefaultTopologyNavigator(context)
            }

            scoped<PathReservationRegistry> {
                val context =
                    getSource<DefaultSimulationContext>()
                        ?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
                PathReservationRegistry(context)
            }

            scoped<PathInfoBuilder> {
                val context =
                    getSource<DefaultSimulationContext>()
                        ?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
                PathInfoBuilder(context)
            }

            scoped<PathReservationService> {
                val context =
                    getSource<DefaultSimulationContext>()
                        ?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
                val navigator: TopologyNavigator = get()
                val registry: PathReservationRegistry = get()
                val pathInfoBuilder: PathInfoBuilder = get()
                DefaultPathReservationService(navigator, context, registry, pathInfoBuilder)
            }

            scoped<TrainNavigationService> {
                val context =
                    getSource<DefaultSimulationContext>()
                        ?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
                val registry: PathReservationRegistry = get()
                DefaultTrainNavigationService(context, registry)
            }
        }
    }
