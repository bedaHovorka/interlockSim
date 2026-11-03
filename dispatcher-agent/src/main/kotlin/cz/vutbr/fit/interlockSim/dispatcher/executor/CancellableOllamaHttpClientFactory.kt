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

/**
 * The HTTP client factory for the dispatcher's Ollama client, pinned to Ktor's CIO engine so that a
 * cancelled request closes its connection (Issue #1341).
 *
 * ## Why the engine is pinned
 *
 * Koog's OllamaClient builds on Ktor's `HttpClient()`, which takes the first engine the
 * `ServiceLoader` finds. koog-agents brings both Apache5 and CIO, and Apache5 came first
 * — so the choice hung on classpath order. Apache5 does not close a cancelled request that was sent
 * on a **reused** keep-alive connection (a fresh connection is torn down). The production client
 * reuses its pooled connection on every dispatcher cycle after the first, so when the per-cycle
 * `withTimeout` in `KoogAgentPlanAdapter` cancelled a `stream=false` `/api/chat` call, the socket
 * stayed open, Ollama (one slot) kept generating, and every later request queued behind it. On
 * 2026-11-01 the abandoned request stayed open 2m45s, until the run shut down.
 *
 * CIO without pipelining (its default) sends each request on a connection of its own and closes
 * that connection when the call is cancelled, so the cycle timeout drops the request at once and
 * Ollama aborts the generation. `OllamaTimeoutAbortTest` measures it against a server that holds
 * its reply. The extra TCP connect per request is negligible on loopback.
 *
 * A new base client per call: closing the Ollama client built from this factory closes that
 * client's own engine, and no engine is shared between clients.
 *
 * ## The one construction funnel
 *
 * Every Ollama client in this module must be built through [cancellableOllamaClient] — passing the
 * factory at each separate construction site would rely on memory, and one forgotten site silently
 * reverts to the Apache5 default again. `DispatcherOllamaClientTripwireTest` fails the build when
 * any `OllamaClient` construction appears outside this file.
 */
internal fun cancellableOllamaHttpClientFactory(): KtorKoogHttpClient.Factory =
	KtorKoogHttpClient.Factory(baseClient = HttpClient(CIO))

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
): OllamaClient =
	OllamaClient(
		httpClientFactory = cancellableOllamaHttpClientFactory(),
		baseUrl = baseUrl,
		contextWindowStrategy = contextWindowStrategy
	)
