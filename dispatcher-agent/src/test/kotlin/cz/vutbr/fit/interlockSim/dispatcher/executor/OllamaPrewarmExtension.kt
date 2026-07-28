/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.executor

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * JUnit 5 extension that preloads the Ollama model before any test in the annotated class runs.
 *
 * ## Rationale
 *
 * `@Tag("ollama-test")` tests drive real inference through a local Ollama instance.  If the
 * model is cold when the first test fires, the load latency can push the test past its own
 * timeout and cause a spurious failure.  Attaching this extension ensures the model is already
 * resident in Ollama's memory before the first test method begins.
 *
 * ## Usage
 *
 * Add `@ExtendWith(OllamaPrewarmExtension::class)` to every test class that carries
 * `@Tag("ollama-test")` tests:
 *
 * ```kotlin
 * @ExtendWith(OllamaPrewarmExtension::class)
 * class MyOllamaTest {
 *     @Test
 *     @Tag("ollama-test")
 *     fun `real inference test`() { ... }
 * }
 * ```
 *
 * Warm-up failures (e.g. Ollama not running) are non-fatal and are logged at `WARN` level;
 * they do **not** abort the test class.  The Gradle `integrationTest` task already gates
 * inclusion of `@Tag("ollama-test")` tests on Ollama reachability, so a warm-up failure
 * during a non-Ollama run is harmless.
 *
 * @see OllamaModelPrewarmer
 * @since Issue #815 (SP2b.9 warm-up follow-up — Goal 10)
 */
class OllamaPrewarmExtension : BeforeAllCallback {
	override fun beforeAll(context: ExtensionContext) {
		runBlocking {
			OllamaModelPrewarmer.warmUp(OllamaExecutorConfig.forLocalTesting())
		}
	}
}
