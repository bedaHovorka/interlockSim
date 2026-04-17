/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import org.koin.core.component.inject
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test

/**
 * Smoke test for [CommonKoinTestBase].
 *
 * Verifies that:
 * 1. The default module ([commonCoreTestModule]) is started/stopped correctly.
 * 2. A custom module can be provided via [getTestModule] override.
 * 3. Dependencies injected via [inject] delegate are non-null.
 * 4. The test runs on both JVM and linuxX64 targets.
 *
 * @since 2026 (commonTest migration — Task 3c)
 */
class CommonKoinTestBaseSmokeTest : CommonKoinTestBase() {
	// Inject a binding from commonCoreTestModule to prove Koin is running
	private val editingContext: DefaultEditingContext by inject()

	@Test
	fun defaultModuleStartsAndInjectableIsNonNull() {
		assertThat(editingContext).isNotNull()
	}
}

/**
 * Secondary smoke test: proves [getTestModule] override works correctly
 * with a minimal custom module.
 */
class CommonKoinTestBaseCustomModuleSmokeTest : CommonKoinTestBase() {
	// Custom binding provided by [customModule] below
	private val greeting: String by inject()

	override fun getTestModule(): Module =
		module {
			factory<String> { "hello-from-koin" }
		}

	@Test
	fun customModuleBindingIsInjectable() {
		assertThat(greeting).isNotNull()
	}
}
