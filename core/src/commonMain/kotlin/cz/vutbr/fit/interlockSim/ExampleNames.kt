/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

/**
 * The name strings of the built-in examples, in ONE place.
 *
 * Both registries name these examples — the JVM desktop `ExampleRegistry` (console + GUI maps,
 * `Main` args) and the native `fast-sim` CLI's `NativeExampleRegistry` — and every registry test
 * asserts on them. Before Goal 1B SP3 (Issue #1164 + its PR #1267 review round) the names lived
 * as private constants and were retyped as literals in the tests, so a rename had to be chased
 * through both modules.
 *
 * The names are CLI-facing strings: they are what `example <name>` accepts on both platforms.
 * Adding an example means adding its constant here and registering it in the registries.
 */
object ExampleNames {
	const val SHUNTING_LOOP = "shuntingLoop"
	const val SHUNTING_LOOP_AI = "shuntingLoopAI"
	const val SHUNTING_LOOP_SYNC = "shuntingLoopSync"
	const val MULTI_TRAIN_LOOP = "multiTrainLoop"
	const val THREE_TRAIN_LOOP = "threeTrainLoop"
	const val CERVENY_UJEZD = "cervenyUjezd"

	/** The LLM arm on the Cerveny Ujezd station (Goal 1B SP10.1, Issue #1276); JVM only. */
	const val CERVENY_UJEZD_AI = "cervenyUjezdAI"

	/**
	 * The rule-based control arm of [CERVENY_UJEZD_AI] on the same dispatcher-agent stack (Issue #1276);
	 * JVM only. [CERVENY_UJEZD] stays the synchronous, native-parity wiring and records no sweep run.
	 */
	const val CERVENY_UJEZD_RULE_BASED = "cervenyUjezdRuleBased"
}
