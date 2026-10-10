/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Allow-list tripwire for Issue #1152 (SP5 — Goal 1B): reservation targets are chosen in exactly
 * one place, and the production classes that may reach the reservation API are enumerated here.
 *
 * Two facts are pinned over the production sources of `:core` and `:dispatcher-agent`:
 *
 * 1. **Who calls the reservation/target API.** [ALLOWED_TARGET_API_CALLERS] — a new class calling
 *    `reservePath`, `reservePathToAnyNextSemaphore`, `findReservationTargetCandidates` or
 *    `isPathToAnyNextSemaphoreAvailable` fails this test until it is argued for and listed.
 * 2. **Who holds a [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService] handle.**
 *    [ALLOWED_SERVICE_HOLDERS] — `InterlockingFacade` is deliberately *not* the only entry:
 *    `InOutWorker`, `MultiTrainLoop`, `Train`, `DefaultNetworkActuatorPort` and
 *    `RegistryPartialRouteReleaser` reach the service directly, each for its own reason.
 *
 * The point is not that the list is short but that it cannot grow silently: every new entry is a
 * second opinion about which route to set, which is exactly what this sub-project removed.
 *
 * Scope note: `:desktop-ui` is the composition root (it wires the service into
 * `RegistryPartialRouteReleaser`) and the declaring package `context/navigation` implements the
 * API itself, so both are out of scope. Only non-comment source lines are examined — KDoc may
 * name the API freely.
 *
 * @since Issue #1152 (SP5 — Goal 1B)
 */
@DisplayName("Target selection has one entry (allow-list tripwire, Issue #1152)")
class TargetSelectionSingleEntryTest {
	private companion object {
		/**
		 * Production source roots. Listed relative to both plausible working directories — the
		 * `:core` project directory (Gradle's default for its test tasks) and the repository root
		 * — because only the existing ones are scanned.
		 */
		val SOURCE_ROOTS =
			listOf(
				"src/commonMain/kotlin",
				"src/jvmMain/kotlin",
				"../dispatcher-agent/src/main/kotlin",
				"core/src/commonMain/kotlin",
				"core/src/jvmMain/kotlin",
				"dispatcher-agent/src/main/kotlin"
			)

		/** The declaring/implementing package: it *is* the reservation API. */
		const val DECLARING_PACKAGE_PATH = "context/navigation"

		/** The DI providers that hand the service out; they never choose a target themselves. */
		val SERVICE_PROVIDERS = setOf("SimulationEnvironment.kt", "DefaultSimulationContext.kt")

		val TARGET_API_CALL =
			Regex(
				"""\.(reservePath|reservePathToAnyNextSemaphore|findReservationTargetCandidates""" +
					"""|isPathToAnyNextSemaphoreAvailable)\("""
			)

		val SERVICE_HANDLE = Regex("""getPathReservationService\(\)|val\s+\w+:\s*PathReservationService""")

		val ALLOWED_TARGET_API_CALLERS =
			setOf(
				"DefaultInterlockingFacade.kt",
				"DefaultNetworkActuatorPort.kt",
				"InOutWorker.kt",
				"MultiTrainLoop.kt",
				"ShuntingLoop.kt"
			)

		val ALLOWED_SERVICE_HOLDERS =
			setOf(
				"DefaultInterlockingFacade.kt",
				"DefaultNetworkActuatorPort.kt",
				"InOutWorker.kt",
				"MultiTrainLoop.kt",
				"RegistryPartialRouteReleaser.kt",
				"Train.kt"
			)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("only allow-listed classes call the reservation target API")
	fun onlyAllowListedClassesCallTheTargetApi() {
		assertThat(matchingFiles(TARGET_API_CALL), "callers of the reservation target API")
			.isEqualTo(ALLOWED_TARGET_API_CALLERS)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("only allow-listed classes hold a PathReservationService handle")
	fun onlyAllowListedClassesHoldTheService() {
		assertThat(matchingFiles(SERVICE_HANDLE), "holders of a PathReservationService handle")
			.isEqualTo(ALLOWED_SERVICE_HOLDERS)
	}

	/** Names of the in-scope production files with at least one non-comment line matching [pattern]. */
	private fun matchingFiles(pattern: Regex): Set<String> =
		productionSources()
			.filter { file -> file.readLines().any { line -> isCode(line) && pattern.containsMatchIn(line) } }
			.map { it.name }
			.toSet()

	/** Every in-scope production Kotlin file; fails loudly if a source root has moved. */
	private fun productionSources(): List<File> {
		val files =
			SOURCE_ROOTS
				.map { File(it) }
				.filter { it.isDirectory }
				.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
				.filterNot { it.invariantSeparatorsPath.contains(DECLARING_PACKAGE_PATH) }
				.filterNot { it.name in SERVICE_PROVIDERS }
		assertThat(files.size, "scanned production sources (source roots: $SOURCE_ROOTS)").isGreaterThan(0)
		return files
	}

	/** `true` for a line that is neither blank nor a comment/KDoc line. */
	private fun isCode(line: String): Boolean {
		val trimmed = line.trim()
		return trimmed.isNotEmpty() &&
			!trimmed.startsWith("//") &&
			!trimmed.startsWith("*") &&
			!trimmed.startsWith("/*")
	}
}
