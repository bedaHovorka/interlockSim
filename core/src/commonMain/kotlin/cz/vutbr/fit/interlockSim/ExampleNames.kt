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
 * The names are CLI-facing strings: they are what `example <name>` accepts. A name does not
 * always exist on both platforms — the native `fast-sim` registry accepts the subset it can build
 * without JVM-only wiring, and a constant's KDoc says when the name is JVM only.
 * Adding an example means adding its constant here and registering it in the registries.
 */
object ExampleNames {
	const val SHUNTING_LOOP = "shuntingLoop"
	const val SHUNTING_LOOP_AI = "shuntingLoopAI"
	const val SHUNTING_LOOP_SYNC = "shuntingLoopSync"
	const val MULTI_TRAIN_LOOP = "multiTrainLoop"
	const val THREE_TRAIN_LOOP = "threeTrainLoop"

	/**
	 * The rule-based control arm on the Cerveny Ujezd station (Goal 1B SP3, Issue #1150): the
	 * [SHUNTING_LOOP] wiring on `cerveny_ujezd.xml`. Since Issue #1276 its console arm runs the
	 * dispatcher-agent stack next to its LLM twin [CERVENY_UJEZD_AI]; the native target keeps the
	 * synchronous build, which [CERVENY_UJEZD_SYNC] mirrors on the JVM.
	 */
	const val CERVENY_UJEZD = "cervenyUjezd"

	/** The LLM arm on the Cerveny Ujezd station (Goal 1B SP10.1, Issue #1276); JVM only. */
	const val CERVENY_UJEZD_AI = "cervenyUjezdAI"

	/**
	 * The synchronous parity twin of [CERVENY_UJEZD] on `cerveny_ujezd.xml`, JVM only, like
	 * [SHUNTING_LOOP_SYNC]: the console cervenyUjezd runs the dispatcher-agent stack (Issue #1276),
	 * whose wiring does not exist on the native target, so the cross-platform parity comparison
	 * runs this twin's JVM output against the native cervenyUjezd
	 * (both built with `wireSynchronousDispatcher`).
	 */
	const val CERVENY_UJEZD_SYNC = "cervenyUjezdSync"
}
