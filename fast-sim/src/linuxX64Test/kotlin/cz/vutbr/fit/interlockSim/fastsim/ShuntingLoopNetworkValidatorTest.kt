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
 * - Červený Újezd is accepted (Issue #1163); Praha, which ShuntingLoop cannot run, is rejected (Issue #1162).
 * - A non-compatible network (e.g., LINEAR_TRACK_XML) produces actionable issues.
 * - A home signal facing its InOut is named as the reason (signal direction, Issue #1272).
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
	fun `validateShuntingLoopCompatible returns empty list for canonical vyhybna network`() {
		factory.createFromXml(NetworkResources.VYHYBNA_XML).use { ctx ->
			val issues = ShuntingLoopNetworkValidator.validateShuntingLoopCompatible(ctx)
			assertEquals(emptyList(), issues, "vyhybna.xml must be ShuntingLoop-compatible")
		}
	}

	@Test
	fun `validateShuntingLoopCompatible accepts the Cerveny Ujezd station`() {
		factory.createFromXml(NetworkResources.CERVENY_UJEZD_XML).use { ctx ->
			// Four InOuts, two per side: the generator pairs opposite sides (SP3.1, Issue #1163).
			val issues = ShuntingLoopNetworkValidator.validateShuntingLoopCompatible(ctx)
			assertEquals(emptyList(), issues, "cerveny_ujezd.xml must be ShuntingLoop-compatible")
		}
	}

	@Test
	fun `validateShuntingLoopCompatible rejects the Praha station`() {
		factory.createFromXml(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML).use { ctx ->
			val issues = ShuntingLoopNetworkValidator.validateShuntingLoopCompatible(ctx)
			assertTrue(issues.any { it.contains("no home signal") }, "expected a home-signal issue; got: $issues")
			assertTrue(
				issues.any { it.contains("neither a home signal nor a platform-track end") },
				"expected an unattached-signal issue; got: $issues"
			)
		}
	}

	@Test
	fun `validateShuntingLoopCompatible names a home signal that faces its InOut`() {
		factory.createFromXml(NetworkResources.STATION_HOME_SIGNAL_FACING_AWAY_XML).use { ctx ->
			// Signal direction (Issue #1272): zA ends the block at InOut A but faces A.
			val issues = ShuntingLoopNetworkValidator.validateShuntingLoopCompatible(ctx)
			assertTrue(
				issues.any { it.contains("kA") && it.contains("no home signal") && it.contains("zA faces the InOut") },
				"expected the misdirected home signal zA to be named; got: $issues"
			)
		}
	}

	@Test
	fun `validateShuntingLoopCompatible reports actionable issues for linear track network`() {
		factory.createFromXml(NetworkResources.LINEAR_TRACK_XML).use { ctx ->
			val issues = ShuntingLoopNetworkValidator.validateShuntingLoopCompatible(ctx)
			assertTrue(issues.isNotEmpty(), "LINEAR_TRACK_XML must not be ShuntingLoop-compatible")
			// The issues name the station elements the topology lacks.
			assertTrue(issues.any { it.contains("platform track") }, "expected a platform-track issue; got: $issues")
			assertTrue(issues.any { it.contains("no home signal") }, "expected a home-signal issue; got: $issues")
		}
	}
}
