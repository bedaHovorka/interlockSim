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

import ai.koog.prompt.executor.ollama.client.ContextWindowStrategy
import ai.koog.prompt.executor.ollama.client.OllamaClient
import assertk.assertThat
import assertk.assertions.isTrue
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test

/**
 * The lifecycle contract behind PR #1352's review: the Koog factory derives its served client with
 * Ktor's `baseClient.config(...)`, which shares the base client's engine with the copy, and Ktor
 * attaches every client's request job to the engine's request job as a child. Closing only the
 * served copy (what Koog's `OllamaClient.close()` does) then leaves the base client's request job
 * attached, the engine's request job can never complete, CIO's selector manager never stops —
 * and every created-and-closed client leaks a `java.nio` selector (an epoll fd), its queue, and a
 * running processing coroutine.
 *
 * Red-first proven: without the post-construction base-job release in [cancellableOllamaClient]
 * the engine's request job stays open forever and this test's ceiling fails.
 */
class CancellableOllamaClientLifecycleTest {
	private companion object {
		/** The drained CIO engine completes in milliseconds; the ceiling only bounds a red run. */
		const val JOB_CEILING_MILLIS = 5_000L
	}

	@Test
	fun `a closed funnel client completes its CIO engine job within the ceiling`() {
		val owner =
			cancellableOllamaClient(
				baseUrl = "http://127.0.0.1:1",
				contextWindowStrategy = ContextWindowStrategy.Companion.None
			)
		try {
			// Koog's OllamaClient holds its transport as the private `httpClient` field, and the
			// transport exposes the Ktor copy only over the JVM-public `getKtorClient` getter; both
			// are internal to their modules from Kotlin's point of view (the same reflection the
			// engine-pin test takes). `HttpClient.engine` is Ktor's public API, and for CIO the job
			// in its engine context is CIO's requestsJob — the only gate the selector manager's
			// close waits on.
			val transport =
				OllamaClient::class.java
					.getDeclaredField("httpClient")
					.apply { isAccessible = true }
					.get(owner)
			val ktorClient =
				transport.javaClass.getMethod("getKtorClient").invoke(transport) as io.ktor.client.HttpClient
			val engineJob = ktorClient.engine.coroutineContext[Job] as CompletableJob

			owner.close()

			val completed = runBlocking { withTimeoutOrNull(JOB_CEILING_MILLIS) { engineJob.join() } != null }
			assertThat(completed, "the engine job completed after the client closed").isTrue()
		} finally {
			// Idempotent: HttpClient.close() short-circuits on its own closed flag, both when the
			// close above already ran and when reflection failed before any close happened.
			owner.close()
		}
	}
}
