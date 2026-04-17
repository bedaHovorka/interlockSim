package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.hovorka.kdisco.Continuous
import cz.hovorka.kdisco.Process
import cz.hovorka.kdisco.Variable
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Verifies that kDisco 0.3.0 KMP API is accessible on all targets including linuxX64.
 *
 * These tests confirm that kDisco's native klib links correctly and core types
 * can be instantiated or referenced. This is primarily a compilation and linkage
 * check — if these tests compile and run on linuxX64, kDisco native support works.
 *
 * @since 2026-03-21 (KMP Task 10 — kDisco native compatibility)
 */
class KDiscoNativeCompatibilityTest : KoinComponent {
	@BeforeTest
	fun setUp() {
		startKoin { modules(commonCoreTestModule) }
	}

	@AfterTest
	fun tearDown() {
		stopKoin()
	}

	@Test
	fun `Variable can be instantiated and state set`() {
		val variable = Variable(42.0)
		assertThat(variable.state).isEqualTo(42.0)

		variable.state = 99.5
		assertThat(variable.state).isEqualTo(99.5)
	}

	@Test
	fun `Variable rate property is accessible`() {
		val variable = Variable(0.0)
		variable.rate = 5.0
		assertThat(variable.rate).isEqualTo(5.0)
	}

	@Test
	fun `Process type is accessible on native`() {
		// Compilation check: verify Process class is resolvable on linuxX64.
		assertThat(Process::class.simpleName).isEqualTo("Process")
	}

	@Test
	fun `Continuous type is accessible on native`() {
		// Compilation check: verify Continuous class is resolvable on linuxX64.
		assertThat(Continuous::class.simpleName).isEqualTo("Continuous")
	}

	@Test
	fun `DefaultSimulationProcessFactory can be created`() {
		val factory = get<SimulationProcessFactory>()
		assertThat(factory).isNotNull()
		assertThat(factory::class.simpleName).isEqualTo("DefaultSimulationProcessFactory")
	}

	@Test
	fun `SimpleIntegration links Continuous with Variables on native`() {
		val position = Variable(0.0)
		val velocity = Variable(10.0)
		val integration = SimpleIntegration(position, velocity)
		// Verify the Continuous subclass was constructed without error.
		assertThat(integration).isNotNull()
	}
}
