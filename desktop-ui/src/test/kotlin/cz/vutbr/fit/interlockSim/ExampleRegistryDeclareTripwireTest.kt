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

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source tripwire for the Koin `Scope.declare` leak (Issue #1348, #1317).
 *
 * In Koin 3.5 a `Scope.declare` of a type with no module `scoped` definition is served to every
 * later context that declares none, even after the declaring context is closed. Such instances
 * must go through `Scope.publish` (`ContextSlots`). `declare` stays safe only for a type the
 * dispatcher-agent module binds `scoped`: there it just refreshes this context's own value.
 *
 * Every `declare` in `ExampleRegistry` must therefore name its type explicitly, and that type must
 * have a `scoped<Type>` definition in `DispatcherAgentModule`.
 */
@DisplayName("ExampleRegistry declares only types with a module scoped definition (#1348)")
class ExampleRegistryDeclareTripwireTest {
	private val exampleRegistry = File("src/main/kotlin/cz/vutbr/fit/interlockSim/ExampleRegistry.kt")
	private val dispatcherAgentModule =
		File("../dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/di/DispatcherAgentModule.kt")

	@Test
	@DisplayName("every declare in ExampleRegistry names a type bound scoped in DispatcherAgentModule")
	fun noLeakyDeclareInExampleRegistry() {
		val scopedTypes = scopedTypes(dispatcherAgentModule.readText())

		assertThat(leakyDeclares(exampleRegistry.readText(), scopedTypes)).isEmpty()
	}

	@Test
	@DisplayName("the scoped-type scan finds the recorder and the snapshot store")
	fun scopedTypeScanFindsTheSafeTypes() {
		val scopedTypes = scopedTypes(dispatcherAgentModule.readText())

		assertThat(scopedTypes).contains("DispatcherRunRecorder")
		assertThat(scopedTypes).contains("RunSnapshotStore")
	}

	@Test
	@DisplayName("the declare scan flags an untyped declare and a declare of an unbound type")
	fun declareScanFlagsLeakyDeclares() {
		val source =
			"""
			scope.declare(aiPlanner)
			// scope.declare(commentedOut)
			scope.declare<SimulationController>(pacingController)
			scope.declare<DispatcherRunRecorder>(recorder)
			""".trimIndent()

		assertThat(leakyDeclares(source, setOf("DispatcherRunRecorder")))
			.containsExactly("scope.declare(aiPlanner)", "scope.declare<SimulationController>(pacingController)")
	}

	private fun scopedTypes(moduleSource: String): Set<String> =
		SCOPED_DEFINITION.findAll(moduleSource).map { it.groupValues[1] }.toSet()

	private fun leakyDeclares(
		source: String,
		scopedTypes: Set<String>
	): List<String> =
		source
			.lines()
			.map(String::trim)
			.filterNot { it.startsWith("//") || it.startsWith("*") }
			.filter { line -> DECLARE_CALL.findAll(line).any { it.groupValues[1] !in scopedTypes } }

	private companion object {
		val SCOPED_DEFINITION = Regex("""\bscoped<\s*(\w+)\s*>""")

		/** A `.declare(` or `.declare<Type>(` call; group 1 is the type, empty when not given. */
		val DECLARE_CALL = Regex("""\.declare\s*(?:<\s*(\w+)\s*>)?\s*\(""")
	}
}
