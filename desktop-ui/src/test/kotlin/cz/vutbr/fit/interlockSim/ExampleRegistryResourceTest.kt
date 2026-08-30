/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Tests for the shared example-resource loading in ExampleRegistry
 */
package cz.vutbr.fit.interlockSim

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.message
import cz.vutbr.fit.interlockSim.context.ContextCreationException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [ExampleRegistry.readExampleXml]: the single helper every shunting-loop
 * example factory uses to load the built-in vyhybna.xml network (PR #1012 review
 * follow-up — the try/catch block was previously copy-pasted at 9 sites).
 */
@DisplayName("Example Registry resource loading")
class ExampleRegistryResourceTest {
	@Test
	fun `readExampleXml returns the built-in shunting-loop network`() {
		// Arrange
		val registry = ExampleRegistry()

		// Act
		val xml = registry.readExampleXml()

		// Assert - the vyhybna.xml network is on the classpath and parseable
		assertThat(xml).isNotEmpty()
		assertThat(xml).contains("<net")
	}

	@Test
	fun `readExampleXml wraps a missing resource as ContextCreationException`() {
		// Arrange
		val registry = ExampleRegistry()
		val missingPath = "no/such/resource.xml"

		// Act & Assert - the raw IllegalArgumentException must not leak to callers
		assertFailure {
			registry.readExampleXml(missingPath)
		}.isInstanceOf<ContextCreationException>()
			.message()
			.isNotNull()
			.contains(missingPath)
	}
}
