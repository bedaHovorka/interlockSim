/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.ContextFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Round-trip helpers for save→load tests (Issue #1035 review): collapse the repeated
 * `saveContext` → `createContext` → `.use` scaffolding into one call.
 *
 * Both helpers assert that the save succeeded before they reload, so a pre-save
 * validation failure (for example too few InOuts) fails at the save step with a clear
 * message instead of surfacing later as a parse error.
 *
 * Both helpers close the LOADED context only. The SOURCE [Context] is owned by the
 * caller — wrap it in `.use` yourself, or the Koin scope leaks (the exact leak class
 * PR #1037 sweeps).
 */
fun ContextFactory.saveAndReloadThroughFile(
	context: Context<*, *>,
	file: File,
	verify: (loaded: Context<*, *>) -> Unit
) {
	assertThat(saveContext(context, file), name = "saveContext must succeed before reload").isTrue()
	createContext(file).use(verify)
}

/**
 * In-memory variant of [saveAndReloadThroughFile]: saves [context] to a
 * [ByteArrayOutputStream], reloads it, and runs [verify] inside the loaded context's
 * `.use`. The loaded context is closed afterwards; the SOURCE [context] stays open —
 * the caller owns it.
 */
fun ContextFactory.saveAndReloadThroughStream(
	context: Context<*, *>,
	verify: (loaded: Context<*, *>) -> Unit
) {
	val outputStream = ByteArrayOutputStream()
	assertThat(saveContext(context, outputStream), name = "saveContext must succeed before reload").isTrue()
	createContext(ByteArrayInputStream(outputStream.toByteArray())).use(verify)
}
