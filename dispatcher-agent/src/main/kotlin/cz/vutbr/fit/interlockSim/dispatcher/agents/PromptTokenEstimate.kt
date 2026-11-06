/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

/**
 * Tokenizer-free estimate of how many tokens a prompt text costs the dispatcher model (Issue #1155).
 *
 * The JVM has no `qwen2.5` tokenizer, and the prompt budget must hold without a live Ollama, so this
 * counts the pieces the Qwen2.5 pre-tokenizer splits a text into: a run of letters, a single digit
 * (Qwen2.5 splits every digit on its own), and a single punctuation character. Whitespace merges into
 * the following piece. Byte-pair merges inside a letter run are ignored.
 *
 * Calibrated on 2026-10-31 against the real `Qwen/Qwen2.5-7B-Instruct` `tokenizer.json` on the rendered
 * REVISED prompts: vyhybna 1,170 estimated vs 1,173 real, Červený Újezd 2,622 vs 2,605, the unbounded
 * Praha prompt 32,004 vs 31,291 — within 3 % in both directions. A plain characters-per-token ratio does
 * not work here: it ranged from 1.5 (Praha's ids such as `SW10-S_8_8`) to 3.9 (prose).
 */
object PromptTokenEstimate {
	private val PIECE = Regex("""[\p{L}]+|\p{N}|[^\s\p{L}\p{N}]""")

	/** Estimated token count of [text]. */
	fun of(text: String): Int = PIECE.findAll(text).count()
}
