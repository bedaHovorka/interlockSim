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
 *
 * Note: [commonCoreTestModule] omits [CollisionDetectionService] because it is a
 * JVM-only concern (it depends on Swing pause dialog in production). The test-safe
 * [DefaultCollisionDetectionService] does not show dialogs and is safe to use here.
 *
 * @since Issue #540 (SP0.1 — Goal 10)
 */
val dispatcherAgentTestModule: Module =
	module {
		// Re-export everything from the common core test module.
		includes(commonCoreTestModule)

		// Add CollisionDetectionService to DefaultSimulationContext scope —
		// missing from commonCoreTestModule but required by DefaultSimulationContext.run().
		scope<DefaultSimulationContext> {
			scoped<CollisionDetectionService> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				DefaultCollisionDetectionService(context, context)
			}
		}
	}
