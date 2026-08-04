/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.di

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultDispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.collision.CollisionDetectionService
import cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Verifies the SP2c.22 (#845) [DispatcherRunRecorder] scope binding in [dispatcherAgentModule].
 *
 * Tests:
 * - The binding is NOT a singleton: two [DefaultSimulationContext] instances each get their
 *   own independent [DispatcherRunRecorder].
 * - The resolved type is [DefaultDispatcherRunRecorder].
 * - Each recorder has a distinct [DispatcherRunRecorder.runId].
 *
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence)
 */
@DisplayName("SP2c.22 DispatcherRunRecorder is scoped, not singleton (#845)")
class DispatcherRunRecorderScopeTest {
	private val xmlContextFactory = XMLContextFactory()
	private val processFactory = DefaultSimulationProcessFactory()

	private val collisionDetectionOnlyTestModule: Module =
		module {
			scope<DefaultSimulationContext> {
				scoped<CollisionDetectionService> {
					val context =
						getSource<DefaultSimulationContext>()
							?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
					DefaultCollisionDetectionService(context, context)
				}
			}
		}

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentModule, commonCoreTestModule, collisionDetectionOnlyTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	private fun loadContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = xmlContextFactory.createContext(xmlStream) as EditingContext
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}

	@Test
	@DisplayName("DispatcherRunRecorder resolves from context scope and is DefaultDispatcherRunRecorder")
	fun recorderResolvesFromScope() {
		loadContext().use { ctx ->
			val recorder = ctx.scope.get<DispatcherRunRecorder>()
			assertThat(recorder).isNotNull()
			assertThat(recorder).isInstanceOf(DefaultDispatcherRunRecorder::class)
		}
	}

	@Test
	@DisplayName("Two different contexts receive independent (non-identical) DispatcherRunRecorder instances")
	fun twoContextsReceiveIndependentRecorders() {
		val ctx1 = loadContext()
		val ctx2 = loadContext()

		val recorder1 = ctx1.scope.get<DispatcherRunRecorder>()
		val recorder2 = ctx2.scope.get<DispatcherRunRecorder>()

		// Must be different object instances (scoped, not singleton)
		assert(recorder1 !== recorder2) {
			"DispatcherRunRecorder must NOT be a singleton — ctx1 and ctx2 must have independent instances"
		}

		// Each run must have a distinct runId
		assert(recorder1.runId != recorder2.runId) {
			"Each context must have a unique runId; got runId=${recorder1.runId} for both"
		}

		ctx1.close()
		ctx2.close()
	}
}
