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
 * Contract tests for the [saveAndReloadThroughFile] round-trip helper introduced by the
 * EditingContext leak sweep (Issue #1035, PR #1037): it must close the loaded
 * context's Koin scope while leaving the caller's source context open, and it must fail at the
 * save step — without invoking the verify lambda or writing a file — when the source context
 * cannot be saved.
 *
 * The [Context.close] semantics removed from this class in the Issue #1046 dedupe (close
 * closes the Koin scope, close is idempotent) are pinned once by [KoinTestBaseCleanupContractTest]
 * via the `tracked()` / `tearDownKoin()` path. What stays is the one `.use {}` case that path
 * cannot pin: a failure inside the block must still close the scope — Kotlin stdlib
 * `AutoCloseable.use` (try/finally), exercised through an EditingContext.
 *
 * Uses light fixtures — the contract is about the Koin scope's lifecycle, not railway content.
 */
@DisplayName("EditingContext cleanup contract (Issue #1035)")
class EditingContextCleanupContractTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()

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
	fun `a round trip closes the loaded scope and leaves the source scope to its caller`(
		@TempDir tempDir: File
	) {
		var loadedByHelper: Context<*, *>? = null

		TestFixtures.loadShuntingEditingContext(editingContextFactory).use { source ->
			editingContextFactory.saveAndReloadThroughFile(source, File(tempDir, "cleanup-contract.xml")) { loaded ->
				assertThat(loaded.scope.closed, name = "loaded scope open during verify").isFalse()
				loadedByHelper = loaded
			}

			assertThat(source.scope.closed, name = "source scope left open by the round trip").isFalse()
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
