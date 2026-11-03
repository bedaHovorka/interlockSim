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

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A loopback stand-in for Ollama's `/api/chat` that answers the first request at once with
 * [answerBody] and then holds every later one without a reply, the way a runaway `stream=false`
 * generation does (Issue #1341).
 *
 * Built on a raw [ServerSocket] rather than MockWebServer because the one thing the tests need is
 * the exact moment the client closes the held connection: a blocking read on the socket returns
 * `-1` (FIN) or throws (RST) at that moment. Connections are served HTTP/1.1 keep-alive, so a
 * client with a connection pool sends the held request on the connection the answered ones
 * already used — the production shape of the 2026-11-01 incident. Every client this fixture serves
 * (Ktor CIO or Apache5 through Koog's OllamaClient) sends `Content-Length` on its requests, which
 * is the only request shape the reader understands.
 *
 * A held request waits at most 30 s for the client to close; if the client never does, the close
 * measure ([awaitCloseLatencyAfter]) reports `null`.
 *
 * @property answerBody Body of the answering response; defaults to a completed `/api/chat` reply.
 */
class HoldingOllamaServer(
	private val answerBody: String = ANSWER_BODY
) : AutoCloseable {
	companion object {
		/** Ollama holds a runaway generation for minutes; 30 s is far past any test cycle timeout. */
		private const val DEFAULT_HOLD_MILLIS = 30_000

		/** Millis-to-nanos multiplier for the tests that compute a [System.nanoTime] reference. */
		const val NANOS_PER_MILLI = 1_000_000L

		private const val ANSWER_BODY =
			"""{"model":"qwen2.5:7b-instruct","message":{"role":"assistant","content":"done"},"done":true}"""
		private val CONTENT_LENGTH = Regex("(?i)content-length:\\s*(\\d+)")
		private const val HEADER_END = "\r\n\r\n"
	}

	private val serverSocket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
	private val sockets = CopyOnWriteArrayList<Socket>()
	private val requestCount = AtomicInteger()
	private val heldClosedAt = AtomicLong(-1)
	private val held = CountDownLatch(1)
	private val heldClosed = CountDownLatch(1)

	/** Base URL to hand to the Ollama client. */
	val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

	/** `System.nanoTime()` at which the client closed the held connection, or `null` if it has not. */
	val heldConnectionClosedAtNanos: Long?
		get() = heldClosedAt.get().takeIf { it >= 0 }

	init {
		thread(isDaemon = true, name = "holding-ollama-accept") {
			while (!serverSocket.isClosed) {
				val socket =
					try {
						serverSocket.accept()
					} catch (_: IOException) {
						break
					}
				sockets.add(socket)
				thread(isDaemon = true, name = "holding-ollama-conn") { serve(socket) }
			}
		}
	}

	/** Blocks until a request is being held, or [timeoutMillis] passes; returns whether one is. */
	fun awaitHeldRequest(timeoutMillis: Long): Boolean = held.await(timeoutMillis, TimeUnit.MILLISECONDS)

	/** Blocks until the client closes the held connection, or [timeoutMillis] passes. */
	fun awaitHeldConnectionClosed(timeoutMillis: Long): Boolean = heldClosed.await(timeoutMillis, TimeUnit.MILLISECONDS)

	/**
	 * Awaits the close of the held connection for up to [ceilingMillis] and measures how long after
	 * [referenceNanos] it closed — the pattern both cancellation tests assert on. Returns the close
	 * latency in milliseconds, or `null` when the client never closed within the ceiling.
	 */
	fun awaitCloseLatencyAfter(
		referenceNanos: Long,
		ceilingMillis: Long
	): Long? {
		awaitHeldConnectionClosed(ceilingMillis)
		val closedAt = heldClosedAt.get().takeIf { it >= 0 } ?: return null
		return (closedAt - referenceNanos) / NANOS_PER_MILLI
	}

	private fun serve(socket: Socket) {
		socket.use {
			val input = BufferedInputStream(socket.getInputStream())
			while (readRequest(input)) {
				if (requestCount.incrementAndGet() <= 1) {
					answer(socket)
				} else {
					hold(socket, input)
					return
				}
			}
		}
	}

	private fun answer(socket: Socket) {
		val body = answerBody.toByteArray()
		val head =
			"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
				"Content-Length: ${body.size}\r\n\r\n"
		socket.getOutputStream().apply {
			write(head.toByteArray())
			write(body)
			flush()
		}
	}

	private fun hold(
		socket: Socket,
		input: InputStream
	) {
		socket.soTimeout = DEFAULT_HOLD_MILLIS
		held.countDown()
		try {
			// The client sends nothing more on this connection, so the read ends only when it closes.
			input.read()
			markClosed()
		} catch (_: SocketTimeoutException) {
			// Still open after the whole hold: awaitCloseLatencyAfter keeps reporting null.
		} catch (_: IOException) {
			markClosed()
		}
	}

	private fun markClosed() {
		heldClosedAt.compareAndSet(-1, System.nanoTime())
		heldClosed.countDown()
	}

	/**
	 * Reads one HTTP request (head plus body); returns `false` when the client closed the connection
	 * without completing a request.
	 */
	private fun readRequest(input: InputStream): Boolean {
		val head = StringBuilder()
		while (!head.endsWith(HEADER_END)) {
			val b = input.read()
			if (b < 0) return false
			head.append(b.toChar())
		}
		val length =
			CONTENT_LENGTH
				.find(head)
				?.groupValues
				?.get(1)
				?.toInt()
		return length == null || input.readNBytes(length).size == length
	}

	override fun close() {
		serverSocket.close()
		sockets.forEach { runCatching { it.close() } }
	}
}
