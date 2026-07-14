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

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.collision.CollisionDetectionService
import cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin test module for `:dispatcher-agent` integration tests.
 *
 * Extends [commonCoreTestModule] with JVM-specific scoped services that
 * [DefaultSimulationContext.run] requires but [commonCoreTestModule] does not provide:
 * - [CollisionDetectionService] — scoped to [DefaultSimulationContext]
 * - [NetworkPerceptionPort] — scoped to [DefaultSimulationContext] (SP1.4)
 * - [NetworkActuatorPort] — scoped to [DefaultSimulationContext] (SP1.4)
 *
 * Note: [commonCoreTestModule] omits [CollisionDetectionService] because it is a
 * JVM-only concern (it depends on Swing pause dialog in production). The test-safe
 * [DefaultCollisionDetectionService] does not show dialogs and is safe to use here.
 *
 * The perception and actuator ports are test doubles that provide simulation-backed
 * implementations for integration testing the dispatcher agent (SP1.4).
 *
 * @since Issue #540 (SP0.1 — Goal 10); SP1.4 (#549) adds port bindings for testing
 */
val dispatcherAgentTestModule: Module =
	module {
		// Re-export everything from the common core test module.
		includes(commonCoreTestModule)

		// Add scoped services to DefaultSimulationContext scope —
		// these are missing from commonCoreTestModule but required by integration tests.

		scope<DefaultSimulationContext> {
			// SP1.4: NetworkPerceptionPort for test contexts
			// Reads signal/block state from the simulation environment.
			scoped<NetworkPerceptionPort> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				DefaultNetworkPerceptionPort(
					env = context,
					activeTrains = { emptyList() } // TODO: SP1.5 populate per main process type
				)
			}

			// SP1.4: NetworkActuatorPort for test contexts
			// Commands for routes, signals, and switches via interlocking.
			scoped<NetworkActuatorPort> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				DefaultNetworkActuatorPort(env = context)
			}

			// SP0.1+: CollisionDetectionService — scoped to context
			// Missing from commonCoreTestModule but required by DefaultSimulationContext.run().
			scoped<CollisionDetectionService> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				DefaultCollisionDetectionService(context, context)
			}
		}
	}
