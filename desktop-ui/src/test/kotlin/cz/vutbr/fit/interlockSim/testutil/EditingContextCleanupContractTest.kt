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
 * Contract tests for [JvmEditingContextFactory.saveAndReloadThroughFile], the round-trip helper
 * introduced by the EditingContext leak sweep (Issue #1035, PR #1037): it must close the loaded
 * context's Koin scope while leaving the caller's source context open, and it must fail at the
 * save step — without invoking the verify lambda or writing a file — when the source context
 * cannot be saved.
 *
 * The `.use {}` cleanup contract that this class used to duplicate (closes on the happy path,
 * closes on failure, is safe to call twice) is pinned once by [KoinTestBaseCleanupContractTest]
 * via the `tracked()` / `tearDownKoin()` path (Issue #1046).
 *
 * Uses light fixtures — the contract is about the Koin scope's lifecycle, not railway content.
 */
@DisplayName("EditingContext round-trip cleanup contract (Issue #1035)")
class EditingContextCleanupContractTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()

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
