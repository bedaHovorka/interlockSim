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
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.dispatcher.di.MainProcessDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.dispatcher.di.mainProcessActiveTrains
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationSource
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin test module for `:dispatcher-agent` integration tests.
 *
 * Extends [commonCoreTestModule] with JVM-specific scoped services that
 * [DefaultSimulationContext.run] requires but [commonCoreTestModule] does not provide:
 * - [NetworkPerceptionPort] — scoped to [DefaultSimulationContext] (SP1.4)
 * - [NetworkActuatorPort] — scoped to [DefaultSimulationContext] (SP1.4)
 *
 * `CollisionDetectionService` comes from [commonCoreTestModule], via
 * `sharedSimulationTestScopesModule` (Issue #1029) — the test-safe
 * `DefaultCollisionDetectionService`, which never shows a Swing pause dialog.
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
					// SP1.4b follow-up (PR #769 review): interface-based lookup, no reflection.
					// Shared with dispatcherAgentModule via mainProcessActiveTrains().
					activeTrains = { mainProcessActiveTrains(context) }
				)
			}

			// SP1.4: NetworkActuatorPort for test contexts
			// Commands for routes, signals, and switches via interlocking.
			scoped<NetworkActuatorPort> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				// SP3.5 (Issue #573): wire InterlockingFacade as the single chokepoint.
				// The facade is scoped by sharedSimulationTestScopesModule (same binding as
				// production CoreModule) so it is guaranteed to exist here.
				DefaultNetworkActuatorPort(
					env = context,
					interlockingFacade = context.scope.get<InterlockingFacade>()
				)
			}

			// SP4.2 (Issue #564): Late-bound pacing controller — kept in sync with
			// dispatcherAgentModule so scope-resolution tests exercise the same binding.
			scoped<DelegatingSimulationController> { DelegatingSimulationController() }

			// Goal 10 dispatcher-cannot-approve-trains fix: DispatchLoopSensorPort for test
			// contexts -- kept in sync with dispatcherAgentModule's binding.
			scoped<DispatchLoopSensorPort> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				MainProcessDispatchLoopSensorPort(context)
			}

			// SP2c.1 (Issue #824): DispatcherObservationProjector for test contexts -- kept in
			// sync with dispatcherAgentModule's binding. PathReservationRegistry comes from
			// commonCoreTestModule (re-exported above), which shares this same scope.
			scoped<DispatcherObservationProjector> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				DispatcherObservationProjector(
					perceptionPort = get(),
					dispatchLoopSensorPort = get(),
					pathReservationRegistry = get<PathReservationRegistry>(),
					environment = context
				)
			}
			scoped<DispatcherObservationSource> { get<DispatcherObservationProjector>() }
		}
	}
