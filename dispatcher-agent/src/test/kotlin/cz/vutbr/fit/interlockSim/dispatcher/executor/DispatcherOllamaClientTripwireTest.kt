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

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThanOrEqualTo
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * A source-scan tripwire (repo precedent: `:core:docLineAnchorCheck`) for the #1341 engine pin:
 * the Ollama HTTP engine must stay pinned to Ktor CIO, and the pin takes effect only at client
 * construction, so [cancellableOllamaClient] is the module's one construction funnel. Nothing else
 * in `src/main` may construct an Ollama model client directly — Ktor would resolve its default
 * engine from the ServiceLoader (Apache5) again, and the 2026-11-01 incident shape returns.
 *
 * The behavior tests ([OllamaTimeoutAbortTest], [OllamaConnectivityCheckerCancellationTest]) guard
 * today's two wires; this tripwire covers the NEXT construction site someone writes.
 */
class DispatcherOllamaClientTripwireTest {
	@Test
	fun `every model client construction in main goes through the cancellable funnel`() {
		val funnelFileName = "CancellableOllamaHttpClientFactory.kt"
		val construction = Regex("""\bOllamaClient\(""")
		val files =
			Files.walk(Path.of("src/main/kotlin")).use { walk ->
				walk
					.filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
					.toList()
			}
		val constructionFiles =
			files.flatMap { file ->
				construction.findAll(Files.readString(file)).map { _ -> file.fileName.toString() }
			}

		val outsideFunnel = constructionFiles.filterNot { it == funnelFileName }
		assertThat(
			outsideFunnel,
			"no Ollama client construction in main outside the cancellable funnel (Issue #1341): " +
				"build model clients through cancellableOllamaClient so the CIO engine pin holds"
		).isEmpty()
		assertThat(constructionFiles.count { it == funnelFileName }, "the funnel itself constructs the client")
			.isGreaterThanOrEqualTo(1)
	}
}
