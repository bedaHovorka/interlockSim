/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tripwire for the window-layout persistence wiring (PR #1430 review): [Main] must load
 * [cz.vutbr.fit.interlockSim.di.windowLayoutModule] beside [cz.vutbr.fit.interlockSim.di.guiModule]
 * in the main Koin context. Deleting the load silently disables the remembered window layout with
 * no failing behavior test (the store is only reached through the frame's constructor argument),
 * so this fails the build instead.
 */
class MainLayoutModuleTripwireTest {
	@Test
	fun windowLayoutModuleStaysLoadedBesideGuiModule() {
		val source = File("src/main/kotlin/cz/vutbr/fit/interlockSim/Main.kt").readText()

		assertThat(source).contains("loadModules(listOf(guiModule, windowLayoutModule))")
	}

	@Test
	fun mainFileIsWhereTheTripwireLooks() {
		// If Main.kt moves, this tells the next reader why the tripwire above lost its subject.
		assertThat(File("src/main/kotlin/cz/vutbr/fit/interlockSim/Main.kt").exists()).isEqualTo(true)
	}
}
