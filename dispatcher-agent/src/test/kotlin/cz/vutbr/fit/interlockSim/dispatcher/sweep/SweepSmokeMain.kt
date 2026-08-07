/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.sweep

import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Stand-in child process for [ForkedJvmSweepProcessRunnerTest] (SP2c.24 review follow-up, #847).
 *
 * [ForkedJvmSweepProcessRunner] forks a real JVM, so exercising it needs a real main class to fork
 * *into*. Using the production `Main` would drag a whole simulation — and an Ollama dependency for
 * the AI examples — into a unit test; this class instead does only what the runner's contract can
 * be observed through: it proves the child was launched, that the `-D` properties reached it, that
 * its stdout landed in the redirected log file, and (on demand) that a child overrunning its budget
 * is actually killed.
 *
 * It deliberately mimics the production argument shape the runner builds — `example <name> <end>` —
 * so a change to that shape breaks this test rather than passing silently.
 */
object SweepSmokeMain {
	/** Set to a file path: the child writes its arguments there, proving it ran and what it got. */
	const val SENTINEL_PROPERTY: String = "sweep.smoke.sentinel"

	/** Set to any value: the child sleeps far past any test timeout so the kill path is exercised. */
	const val SLEEP_PROPERTY: String = "sweep.smoke.sleep"

	private const val SLEEP_MILLIS: Long = 600_000L

	@JvmStatic
	fun main(args: Array<String>) {
		// Goes to stdout, which the runner redirects into the request's log file — that redirection
		// is part of what the test asserts, so the child must print something.
		println("SweepSmokeMain args=${args.joinToString(" ")}")

		System.getProperty(SENTINEL_PROPERTY)?.let { sentinel ->
			Path.of(sentinel).writeText(args.joinToString(" "))
		}

		if (System.getProperty(SLEEP_PROPERTY) != null) {
			Thread.sleep(SLEEP_MILLIS)
		}
	}
}
