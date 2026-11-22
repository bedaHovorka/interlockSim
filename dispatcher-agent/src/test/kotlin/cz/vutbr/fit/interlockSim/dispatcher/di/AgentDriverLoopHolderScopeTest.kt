/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.di

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.AgentDriverLoop
import cz.vutbr.fit.interlockSim.dispatcher.AgentDriverLoopHolder
import cz.vutbr.fit.interlockSim.dispatcher.agentDriverLoopOrNull
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.module.Module

/**
 * Verifies the per-context [AgentDriverLoopHolder] binding in [dispatcherAgentModule] (Issue #1317).
 *
 * A loop installed into one context's holder must never be visible through another context's
 * scope — neither while the first context is open nor after it is closed.
 */
@DisplayName("AgentDriverLoopHolder is scoped per simulation context (#1317)")
class AgentDriverLoopHolderScopeTest : DispatcherKoinTestBase() {
	override fun getTestModules(): List<Module> = listOf(dispatcherAgentModule, commonCoreTestModule)

	private fun loadContext(): DefaultSimulationContext = TestFixtures.newShuntingSimulationContext().tracked()

	private fun newLoop(): AgentDriverLoop = AgentDriverLoop(isActive = { false }, runCycle = { true })

	@Test
	@DisplayName("a context without an installed loop resolves no loop")
	fun noLoopUntilInstalled() {
		val context = loadContext()

		assertThat(context.scope.agentDriverLoopOrNull()).isNull()
	}

	@Test
	@DisplayName("the installed loop is resolved from the same context's scope")
	fun installedLoopIsResolved() {
		val context = loadContext()
		val loop = newLoop()

		context.scope.get<AgentDriverLoopHolder>().install(loop)

		assertThat(context.scope.agentDriverLoopOrNull()).isSameInstanceAs(loop)
	}

	@Test
	@DisplayName("two contexts get independent holders")
	fun holdersAreNotShared() {
		val first = loadContext()
		val second = loadContext()

		assertThat(second.scope.get<AgentDriverLoopHolder>())
			.isNotSameInstanceAs(first.scope.get<AgentDriverLoopHolder>())
	}

	@Test
	@DisplayName("a loop installed in one context is not visible to another, open or closed")
	fun installedLoopDoesNotLeakToAnotherContext() {
		val first = loadContext()
		first.scope.get<AgentDriverLoopHolder>().install(newLoop())
		val second = loadContext()

		assertThat(second.scope.agentDriverLoopOrNull(), "while the first context is open").isNull()
		first.close()
		val third = loadContext()
		assertThat(third.scope.agentDriverLoopOrNull(), "after the first context is closed").isNull()
	}

	@Test
	@DisplayName("a closed context resolves no loop instead of throwing")
	fun closedContextResolvesNoLoop() {
		val context = loadContext()
		context.scope.get<AgentDriverLoopHolder>().install(newLoop())
		context.close()

		assertThat(context.scope.agentDriverLoopOrNull()).isNull()
	}

	@Test
	@DisplayName("a second install into the same context is rejected")
	fun secondInstallIsRejected() {
		val holder = AgentDriverLoopHolder()
		holder.install(newLoop())

		assertFailure { holder.install(newLoop()) }.isInstanceOf(IllegalStateException::class)
	}
}
