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

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.ollama.client.ContextWindowStrategy
import ai.koog.prompt.message.Message
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The probes of the agent-architect ruling on Issue #1342 (option A): Koog 1.1.1 drops Ollama's
 * `done_reason`, so [OllamaReplyObserver] reads it from the `/api/chat` reply on the module's own
 * Ktor base client, and the cycle that owns the reply learns through [OllamaCycleReplies] whether
 * a generation was cut by `num_predict`.
 *
 * - P1: the observer reads `done_reason`/`eval_count`, and Koog still decodes the same reply.
 * - P2: the observer survives `baseClient.config(...)` inside `KtorKoogHttpClient.Factory`.
 * - P3: a cycle with several replies is cut when any reply is cut.
 * - P4: the per-cycle sink is reachable from the plugin hook (a coroutine-context element). The
 *   full Koog agent path is proven by `ThinkingTruncatedCycleTest`.
 * - P5: only `/api/chat` is observed; a reply outside a cycle marks nothing.
 */
@DisplayName("Ollama reply observer probes (Issue #1342)")
class OllamaReplyObserverProbeTest {
	private companion object {
		const val MODEL_NAME = "qwen2.5:7b-instruct"
		const val CONTEXT_LENGTH = 32_768L
	}

	private lateinit var server: MockWebServer
	private val chatReplies = ConcurrentLinkedQueue<String>()

	@BeforeEach
	fun setUp() {
		server = MockWebServer()
		server.dispatcher =
			object : Dispatcher() {
				override fun dispatch(request: RecordedRequest): MockResponse {
					val body = if (request.path == "/api/chat") chatReplies.poll() ?: chatReply("stop") else "{}"
					return MockResponse()
						.setResponseCode(200)
						.setBody(body)
						.setHeader("Content-Type", "application/json")
				}
			}
		server.start()
	}

	@AfterEach
	fun tearDown() {
		server.shutdown()
	}

	private fun endpoint(): String = server.url("/").toString().trimEnd('/')

	private fun chatReply(
		doneReason: String,
		evalCount: Int = 7,
		content: String = "done"
	): String =
		"""{"model":"$MODEL_NAME","message":{"role":"assistant","content":"$content"},""" +
			""""done":true,"done_reason":"$doneReason","prompt_eval_count":11,"eval_count":$evalCount}"""

	private val testPrompt = prompt("probe-1342") { user("Hello") }

	private fun execute(): Message.Assistant {
		val client = cancellableOllamaClient(baseUrl = endpoint(), contextWindowStrategy = ContextWindowStrategy.Companion.None)
		try {
			return runBlocking {
				client.execute(testPrompt, OllamaModelFactory.toolCapableModel(MODEL_NAME, CONTEXT_LENGTH), emptyList())
			}
		} finally {
			client.close()
		}
	}

	private fun executeIn(replies: OllamaCycleReplies): Message.Assistant {
		val client = cancellableOllamaClient(baseUrl = endpoint(), contextWindowStrategy = ContextWindowStrategy.Companion.None)
		try {
			return runBlocking {
				withContext(replies) {
					client.execute(testPrompt, OllamaModelFactory.toolCapableModel(MODEL_NAME, CONTEXT_LENGTH), emptyList())
				}
			}
		} finally {
			client.close()
		}
	}

	@Test
	fun `P1 the observer reads done_reason and eval_count and Koog still decodes the reply`() {
		chatReplies.add(chatReply("length", evalCount = 2048, content = "partial"))
		val replies = OllamaCycleReplies()

		val responses = executeIn(replies)

		assertThat(replies.generationCut, "done_reason=length marks the cycle cut").isTrue()
		assertThat(replies.replyCount).isEqualTo(1)
		assertThat(replies.lastEvalCount).isEqualTo(2048)
		assertThat(responses.textContent()).isEqualTo("partial")
	}

	@Test
	fun `P1 a finished reply does not mark the cycle cut`() {
		chatReplies.add(chatReply("stop"))
		val replies = OllamaCycleReplies()

		executeIn(replies)

		assertThat(replies.generationCut).isFalse()
		assertThat(replies.replyCount).isEqualTo(1)
	}

	@Test
	fun `P2 the observer survives the config copy of the base client`() {
		// The production base client, exactly as the Koog factory receives it.
		val base = cancellableOllamaHttpClientFactory().second
		val copy = base.config { }
		chatReplies.add(chatReply("length"))
		val replies = OllamaCycleReplies()
		try {
			val text =
				runBlocking {
					withContext(replies) { copy.post("${endpoint()}/api/chat") { setBody("{}") }.bodyAsText() }
				}
			assertThat(text, "the body is still readable after the observer read it").isEqualTo(chatReply("length"))
		} finally {
			copy.close()
			base.close()
		}
		assertThat(replies.generationCut).isTrue()
	}

	@Test
	fun `P3 a cycle with a finished reply and a cut reply is cut, in either order`() {
		val cutLast = OllamaCycleReplies()
		chatReplies.add(chatReply("stop"))
		chatReplies.add(chatReply("length"))
		executeIn(cutLast)
		executeIn(cutLast)

		val cutFirst = OllamaCycleReplies()
		chatReplies.add(chatReply("length"))
		chatReplies.add(chatReply("stop"))
		executeIn(cutFirst)
		executeIn(cutFirst)

		assertThat(cutLast.generationCut, "cut last").isTrue()
		assertThat(cutLast.replyCount).isEqualTo(2)
		assertThat(cutFirst.generationCut, "cut first").isTrue()
		assertThat(cutFirst.replyCount).isEqualTo(2)
	}

	@Test
	fun `P4 the per-cycle sink is reachable from the plugin hook`() {
		chatReplies.add(chatReply("length"))
		val replies = OllamaCycleReplies()

		executeIn(replies)

		assertThat(replies.replyCount, "the hook found the sink in the caller's coroutine context").isEqualTo(1)
	}

	@Test
	fun `P5 a cut reply outside a cycle marks nothing and does not fail the call`() {
		chatReplies.add(chatReply("length", content = "outside"))

		val responses = execute()

		assertThat(responses.textContent()).isEqualTo("outside")
	}

	@Test
	fun `P5 only api chat is observed - a generate reply with done_reason length marks nothing`() {
		val client = cancellableOllamaHttpClientFactory().second
		val replies = OllamaCycleReplies()
		try {
			runBlocking {
				withContext(replies) {
					// The mock answers every path except /api/chat with "{}"; serve the cut body the
					// prewarmer's num_predict=1 request would get, on its own path.
					server.dispatcher =
						object : Dispatcher() {
							override fun dispatch(request: RecordedRequest): MockResponse =
								MockResponse().setResponseCode(200).setBody(chatReply("length"))
						}
					client.post("${endpoint()}${OllamaModelPrewarmer.GENERATE_PATH}") { setBody("{}") }.bodyAsText()
				}
			}
		} finally {
			client.close()
		}
		assertThat(replies.generationCut).isFalse()
		assertThat(replies.replyCount).isEqualTo(0)
	}

	@Test
	fun `an unparseable chat reply marks nothing`() {
		val client = cancellableOllamaHttpClientFactory().second
		val replies = OllamaCycleReplies()
		chatReplies.add("not json")
		try {
			runBlocking { withContext(replies) { client.post("${endpoint()}/api/chat") { setBody("{}") }.bodyAsText() } }
		} finally {
			client.close()
		}
		assertThat(replies.generationCut).isFalse()
		assertThat(replies.replyCount).isEqualTo(0)
		assertThat(replies.lastEvalCount).isNull()
	}
}
