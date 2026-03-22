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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Smoke tests for the :fast-sim native CLI module.
 *
 * Verifies that the context creation pipeline, example registry, and embedded resources
 * work correctly in the native linuxX64 environment.
 *
 * @since Issue #415 (fast-sim native CLI)
 */
class NativeSmokeTest {

	@BeforeTest
	fun setUp() {
		startKoin { modules(coreModule) }
	}

	@AfterTest
	fun tearDown() {
		stopKoin()
	}

	@Test
	fun `EmbeddedResources VYHYBNA_XML parses to context with 2 InOuts`() {
		val factory = NativeContextFactory()
		val ctx = factory.createFromXml(EmbeddedResources.VYHYBNA_XML)
		try {
			assertEquals(2, ctx.getInOutsList().size)
		} finally {
			ctx.close()
		}
	}

	@Test
	fun `NativeExampleRegistry lists shuntingLoop as available`() {
		assertContains(NativeExampleRegistry.AVAILABLE, "shuntingLoop")
	}

	@Test
	fun `EmbeddedResources VYHYBNA_XML matches NetworkResources canonical definition`() {
		assertEquals(
			NetworkResources.VYHYBNA_XML,
			EmbeddedResources.VYHYBNA_XML,
			"EmbeddedResources.VYHYBNA_XML diverged from NetworkResources.VYHYBNA_XML",
		)
	}

	@Test
	fun `NativeContextFactory createFromXml returns context with 2 InOuts for LINEAR_TRACK_XML`() {
		val factory = NativeContextFactory()
		val ctx = factory.createFromXml(NetworkResources.LINEAR_TRACK_XML)
		try {
			assertEquals(2, ctx.getInOutsList().size)
		} finally {
			ctx.close()
		}
	}

	@Test
	fun `NativeExampleRegistry create throws IllegalArgumentException for unknown example name`() {
		assertFailsWith<IllegalArgumentException> {
			NativeExampleRegistry.create("nonexistent", 10, NativeContextFactory())
		}
	}

	@Test
	fun `NativeExampleRegistry shuntingLoop runs to completion with short endTime`() {
		val factory = NativeContextFactory()
		val ctx = NativeExampleRegistry.create("shuntingLoop", 5, factory)
		try {
			ctx.run()
		} finally {
			ctx.close()
		}
	}

	@Test
	fun `NativeContextFactory createFromFile throws on nonexistent path`() {
		val factory = NativeContextFactory()
		assertFailsWith<Exception> {
			factory.createFromFile("/nonexistent/path/to/network.xml")
		}
	}
}
