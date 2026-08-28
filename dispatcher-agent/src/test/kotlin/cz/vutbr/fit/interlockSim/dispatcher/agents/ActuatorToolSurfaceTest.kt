/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.reflect.KClass

/**
 * Enforces the SP2c.6 four-tool actuator surface (Issue #829) at two levels:
 *
 * 1. **Package scan** — at runtime, enumerates every class in the
 *    `cz.vutbr.fit.interlockSim.dispatcher.agents.tools` package that implements [DomainTool] and
 *    asserts the simple-name set is exactly `{ApproveTrainTool, RequestRouteTool, CancelRouteTool,
 *    NoOpTool}`. This is the literal #829 acceptance criterion: the tools package must contain
 *    exactly the four actuator tools and nothing else that implements [DomainTool] (the
 *    perception/sensor tools were deleted in SP2c.6 — perception now flows through the
 *    sim-thread-captured
 *    [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector], not LLM
 *    tools).
 * 2. **[ActuatorToolSurface.assertExactly] contract** — the construction-time guard used by
 *    [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory] accepts the exact four-tool
 *    set and throws [IllegalArgumentException] for a 5th/perception tool, a wrong name, or a
 *    short list. Drives SonarCloud new-code coverage over the `require` throw branches.
 *
 * @since Issue #829 (SP2c.6 — Goal 10)
 */
@DisplayName("ActuatorToolSurface — the SP2c.6 four-tool actuator surface is exact and enforced")
class ActuatorToolSurfaceTest {
	@Test
	@DisplayName("the tools package contains exactly the four actuator DomainTool classes (SP2c6 #829)")
	fun toolsPackageContainsExactlyFourActuatorTools() {
		val toolClasses = loadDomainToolClassesInPackage(TOOLS_PACKAGE)

		val simpleNames = toolClasses.map { it.simpleName }.toSet()
		assertThat(simpleNames).isEqualTo(
			setOf("ApproveTrainTool", "RequestRouteTool", "CancelRouteTool", "NoOpTool")
		)
	}

	@Test
	@DisplayName("assembleAllTools yields exactly the four actuator tools by name")
	fun assembleAllToolsYieldsExactFourToolSurface() {
		val tools = ToolGroupRegistry().assembleAllTools(emptySet())

		assertThat(tools).hasSize(4)
		assertThat(tools.map { it.name }.toSet()).isEqualTo(ActuatorToolSurface.ALLOWED_NAMES)
	}

	@Test
	@DisplayName("assertExactly accepts the exact four-tool set")
	fun assertExactlyAcceptsTheExactFourToolSurface() {
		val tools = ToolGroupRegistry().assembleAllTools(emptySet())

		// Must not throw — the production KoogAgentFactory relies on this passing at construction.
		ActuatorToolSurface.assertExactly(tools)
	}

	@Test
	@DisplayName("assertExactly throws when a fifth (perception) tool is appended")
	fun assertExactlyThrowsWhenAFifthToolIsAppended() {
		val tools = ToolGroupRegistry().assembleAllTools(emptySet()) + fakeTool("signal_aspect")

		val ex = assertThrows<IllegalArgumentException> { ActuatorToolSurface.assertExactly(tools) }
		assertThat(ex.message ?: "").contains("Actuator tool surface mismatch")
		assertThat(ex.message ?: "").contains("signal_aspect")
	}

	@Test
	@DisplayName("assertExactly throws when a wrong tool name is present")
	fun assertExactlyThrowsWhenAWrongNameIsPresent() {
		// Replace one of the four with a misnamed tool.
		val tools =
			ToolGroupRegistry()
				.assembleAllTools(emptySet())
				.filterNot { it.name == "no_op" } + fakeTool("set_signal_aspect")

		val ex = assertThrows<IllegalArgumentException> { ActuatorToolSurface.assertExactly(tools) }
		assertThat(ex.message ?: "").contains("Actuator tool surface mismatch")
		assertThat(ex.message ?: "").contains("set_signal_aspect")
	}

	@Test
	@DisplayName("assertExactly throws when fewer than four tools are provided")
	fun assertExactlyThrowsWhenCountIsBelowFour() {
		// Only three of the four — missing no_op.
		val tools = ToolGroupRegistry().assembleAllTools(emptySet()).filterNot { it.name == "no_op" }

		assertThrows<IllegalArgumentException> { ActuatorToolSurface.assertExactly(tools) }
	}

	// ── helpers ───────────────────────────────────────────────────────────────

	/**
	 * Enumerates the compiled `.class` files in [packageName] (resolved via the classloader
	 * resource, which is a `file:` URL under Gradle's filesystem class directories during tests),
	 * loads each, and keeps only those assignable to [DomainTool]. This avoids a reflection-scan
	 * library and proves the #829 "exactly four" criterion from the package contents themselves.
	 *
	 * `ToolArgsKt` (the top-level function facade) and `ToolGroupRegistry`/its companion live in
	 * the same package but do not implement [DomainTool], so the [DomainTool] filter excludes them.
	 */
	private fun loadDomainToolClassesInPackage(packageName: String): List<KClass<*>> {
		val resourcePath = packageName.replace('.', '/')
		// Every classpath root, not just the first (Issue #847 round 4). `getResource` returns one
		// URL, and for this package that is the *test* classes directory, which shadowed the main
		// one — so the scan silently read whichever root happened to win. It found zero classes once
		// that directory existed but was empty, and would equally have missed a fifth tool added to
		// the main sources. The guard has to see the union to mean what its KDoc says.
		val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
		val roots = loader.getResources(resourcePath).toList()
		check(roots.isNotEmpty()) {
			"Could not resolve package $packageName on the classpath (resource $resourcePath)"
		}
		val classFiles =
			roots.flatMap { resource ->
				val dir = File(resource.toURI())
				check(dir.isDirectory) { "Package resource $resource is not a directory: $dir" }
				dir.listFiles { f -> f.isFile && f.name.endsWith(".class") }.orEmpty().toList()
			}

		return classFiles
			.mapNotNull { file ->
				val simpleName = file.name.removeSuffix(".class")
				// Companion objects and other nested classes ("Foo$Companion") are not DomainTools;
				// Class.forName resolves the `$` binary name, then the DomainTool filter excludes them.
				val binaryName = "$packageName.$simpleName"
				runCatching { Class.forName(binaryName, false, javaClass.classLoader) }
					.getOrNull()
					?.takeIf { DomainTool::class.java.isAssignableFrom(it) }
					?.kotlin
			}
	}

	/** Minimal [DomainTool] fake used to inject unexpected names into the surface for the throw cases. */
	private fun fakeTool(name: String): DomainTool =
		object : DomainTool {
			override val name: String = name
			override val description: String = "fake"
			override val parameters: List<DomainToolParameter> = emptyList()

			override suspend fun execute(args: Map<String, Any?>): ToolResult = ToolResult.Success("fake")
		}

	private companion object {
		const val TOOLS_PACKAGE = "cz.vutbr.fit.interlockSim.dispatcher.agents.tools"
	}
}
