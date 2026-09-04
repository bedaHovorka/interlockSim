/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.koin.test.inject
import java.io.File

/**
 * Contract tests for the `.use {}` cleanup pattern the EditingContext leak sweep
 * (Issue #1035, PR #1037) established across the desktop-ui test suite: wrapping a
 * context in `.use {}` must close the Koin scope the context owns — on the happy
 * path, when the block fails, and when the scope is already closed. The round-trip
 * tests pin that [saveAndReloadThroughFile] closes the loaded context's scope while
 * the caller keeps the source context, and that it fails at the save step when the
 * source cannot be saved.
 *
 * Uses light fixtures — the contract is about the Koin scope's lifecycle, not
 * railway content.
 */
@DisplayName("EditingContext .use cleanup contract (Issue #1035)")
class EditingContextCleanupContractTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()

	@Test
	fun `use block closes the Koin scope of a factory-created editing context`() {
		val context = editingContextFactory.createEmptyContext()
		assertThat(context.scope.closed, name = "scope open before use").isFalse()

		context.use { }

		assertThat(context.scope.closed, name = "koin scope closed by .use").isTrue()
	}

	@Test
	fun `a failure inside the use block still closes the Koin scope`() {
		val context = editingContextFactory.createEmptyContext()

		assertThrows<AssertionError> {
			context.use {
				throw AssertionError("deliberate failure inside the use block")
			}
		}

		assertThat(context.scope.closed, name = "koin scope closed despite the failure").isTrue()
	}

	@Test
	fun `closing a context twice is safe`() {
		val context = editingContextFactory.createEmptyContext()

		context.use { }
		context.close() // Context.close() is documented idempotent — must not throw

		assertThat(context.scope.closed, name = "koin scope stays closed").isTrue()
	}

	@Test
	fun `a round trip closes the loaded scope and leaves the source scope to its caller`(
		@TempDir tempDir: File
	) {
		var loadedByHelper: Context<*, *>? = null

		TestFixtures.loadShuntingEditingContext(editingContextFactory).use { source ->
			editingContextFactory.saveAndReloadThroughFile(source, File(tempDir, "cleanup-contract.xml")) { loaded ->
				assertThat(loaded.scope.closed, name = "loaded scope open during verify").isFalse()
				loadedByHelper = loaded
			}
		}

		val loaded = requireNotNull(loadedByHelper) { "the round-trip helper must invoke the verify lambda" }
		assertThat(loaded.scope.closed, name = "loaded scope closed by the round-trip helper").isTrue()
	}

	@Test
	fun `a round trip fails at the save step when the source context cannot be saved`(
		@TempDir tempDir: File
	) {
		val file = File(tempDir, "cleanup-contract-unsaveable.xml")

		// An empty context has no InOuts, so the pre-save validation rejects it (saveContext returns false)
		editingContextFactory.createEmptyContext().use { unsaveable ->
			assertThrows<AssertionError> {
				editingContextFactory.saveAndReloadThroughFile(unsaveable, file) {
					throw IllegalStateException("verify must not run when the save fails")
				}
			}
		}

		assertThat(file.exists(), name = "no file written by a rejected save").isFalse()
	}
}
