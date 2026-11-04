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

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.ollama.client.ContextWindowStrategy
import ai.koog.prompt.executor.ollama.client.OllamaClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job

/**
 * The HTTP client factory for the dispatcher's Ollama client, pinned to Ktor's CIO engine so that a
 * cancelled request closes its connection (Issue #1341).
 *
 * ## Why the engine is pinned
 *
 * Koog's OllamaClient builds on Ktor's `HttpClient()`, which takes the first engine the
 * `ServiceLoader` finds. Koog's Ollama client module brings both Apache5 and CIO (via its Ktor
 * wiring), and Apache5 came first — so the choice hung on classpath order. Apache5 does not close
 * a cancelled request that was sent on a **reused** keep-alive connection (a fresh connection is
 * torn down). The production client reuses its pooled connection on every dispatcher cycle after
 * the first, so when the per-cycle `withTimeout` in `KoogAgentPlanAdapter` cancelled a
 * `stream=false` `/api/chat` call, the socket stayed open, Ollama (one slot) kept generating, and
 * every later request queued behind it. On 2026-11-01 the abandoned request stayed open 2m45s,
 * until the run shut down.
 *
 * CIO closes the connection a cancelled request was in flight on, so the cycle timeout drops the
 * request at once and Ollama aborts the generation; the tests measure this on the exact
 * answer-then-hold two-request shape the production client produces, pooled or not. The extra TCP
 * connect per request is negligible on loopback.
 *
 * A new base client per call, returned together with the factory as a pair: Ktor's
 * `baseClient.config(...)` inside the Koog factory derives the served copy from that base, the
 * copy shares the base's engine (so no engine is shared between clients built from different
 * factories), and Ktor attaches every client's request job to the engine's request job as a child.
 * Closing only the served copy (what Koog's `OllamaClient.close()` does) then leaves the base job
 * attached, the engine's request job can never complete, and CIO's selector manager (its epoll fd
 * and its processing coroutine) never stops — one leak per created-and-closed client, including
 * the executor client and every connectivity-check call. The base client sends nothing itself and
 * the served copy keeps the engine's management on close, so releasing the base job after the
 * copy exists lets the engine's request job complete once the Ollama client closes;
 * `cancellableOllamaClient` does exactly that and `CancellableOllamaClientLifecycleTest` pins it.
 *
 * ## The one construction funnel
 *
 * Every Ollama client in this module must be built through [cancellableOllamaClient] — passing the
 * factory at each separate construction site would rely on memory, and one forgotten site silently
 * reverts to the Apache5 default again. `DispatcherOllamaClientTripwireTest` fails the build when
 * any `OllamaClient` construction appears outside this file.
 */
internal fun cancellableOllamaHttpClientFactory(): Pair<KtorKoogHttpClient.Factory, HttpClient> {
	val baseClient = HttpClient(CIO)
	return KtorKoogHttpClient.Factory(baseClient = baseClient) to baseClient
}

/**
 * The one construction funnel for the module's Ollama clients ([OllamaClient] is Koog's class):
 * always on the engine pinned by [cancellableOllamaHttpClientFactory]. Defaults to Koog's own
 * [ContextWindowStrategy.None] so callers that need no context window handling need no extra
 * argument (`OllamaConnectivityChecker.check`); callers that do pass theirs explicitly
 * (`OllamaSimpleExecutor`).
 */
internal fun cancellableOllamaClient(
	baseUrl: String,
	contextWindowStrategy: ContextWindowStrategy = ContextWindowStrategy.Companion.None
): OllamaClient {
	val (factory, baseClient) = cancellableOllamaHttpClientFactory()
	val client =
		OllamaClient(
			httpClientFactory = factory,
			baseUrl = baseUrl,
			contextWindowStrategy = contextWindowStrategy
		)
	// Release the base client's request job only now, after the served copy exists. Ktor derives
	// the copy with `baseClient.config(...)`: it shares the base's engine, and both clients' request
	// jobs are attached children of the engine's request job, so a closed client would leave the
	// base job behind and CIO's selector manager (its epoll fd and its processing coroutine) would
	// never stop. The base client sends nothing itself, and the served copy keeps the engine's
	// management on close, so releasing this job after the copy exists lets the engine's request
	// job complete once the OllamaClient closes. The order is load-bearing: completed before the
	// copy exists, every request of the copy dies immediately with "Engine failed" (probe-proven
	// on the resolved Koog/Ktor versions).
	(baseClient.coroutineContext[Job] as? CompletableJob)?.complete()
	return client
}
