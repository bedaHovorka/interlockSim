/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.fastsim

import cz.vutbr.fit.interlockSim.di.coreModule
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [ShuntingLoopNetworkValidator] (Issue #435).
 *
 * Verifies that:
 * - A vyhybna-compatible network produces an empty issue list.
 * - A non-compatible network (e.g., LINEAR_TRACK_XML) produces actionable issues.
 */
class ShuntingLoopNetworkValidatorTest {

	private val factory = NativeContextFactory()

	@BeforeTest
	fun setUp() {
		startKoin { modules(coreModule) }
	}

	@AfterTest
	fun tearDown() {
		stopKoin()
	}

	@Test
	fun `validateVyhybnaCompatible returns empty list for canonical vyhybna network`() {
		val ctx = factory.createFromXml(NetworkResources.VYHYBNA_XML)
		try {
			val issues = ShuntingLoopNetworkValidator.validateVyhybnaCompatible(ctx)
			assertEquals(emptyList(), issues, "vyhybna.xml must be vyhybna-compatible")
		} finally {
			ctx.close()
		}
	}

	@Test
	fun `validateVyhybnaCompatible reports actionable issues for linear track network`() {
		val ctx = factory.createFromXml(NetworkResources.LINEAR_TRACK_XML)
		try {
			val issues = ShuntingLoopNetworkValidator.validateVyhybnaCompatible(ctx)
			assertTrue(issues.isNotEmpty(), "LINEAR_TRACK_XML must not be vyhybna-compatible")
			// Must include the specific coordinate that's missing (30, 8) — an InOut.
			assertTrue(
				issues.any { it.contains("(30, 8)") && it.contains("InOut") },
				"expected issue mentioning missing InOut at (30, 8); got: $issues",
			)
		} finally {
			ctx.close()
		}
	}
}
