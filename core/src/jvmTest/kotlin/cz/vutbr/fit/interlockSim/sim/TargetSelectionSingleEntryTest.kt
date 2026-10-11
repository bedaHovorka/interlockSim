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
 * one place, and the production classes that may reach the target-selection machinery are
 * enumerated here.
 *
 * Three facts are pinned over the production sources of `:core` and `:dispatcher-agent`:
 *
 * 1. **Who calls the reservation/target API.** [ALLOWED_TARGET_API_CALLERS] — a new class calling
 *    `reservePath`, `reservePathToAnyNextSemaphore`, `findReservationTargetCandidates` or
 *    `isPathToAnyNextSemaphoreAvailable` fails this test until it is argued for and listed.
 * 2. **Who stores a [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService].**
 *    [ALLOWED_SERVICE_HOLDERS] — `InterlockingFacade` is deliberately *not* the only entry:
 *    `InOutWorker`, `MultiTrainLoop`, `Train`, `DefaultNetworkActuatorPort` and
 *    `RegistryPartialRouteReleaser` reach the service directly, each for its own reason; the three
 *    DI wiring files construct and hand out the service without ever choosing a target themselves.
 * 3. **Who reads [cz.vutbr.fit.interlockSim.sim.BlockInputObservation.candidateTargets] in
 *    production.** [ALLOWED_CANDIDATE_TARGET_FILES] — the vocabulary itself
 *    (`DispatchObservation.kt`), the shell that reports it (`ShuntingLoop.kt`), and the single
 *    selection seam (`ReservationTargetPolicy.kt`). A new dispatcher that selects with
 *    `input.candidateTargets.firstOrNull { … }` fails here: selecting from the raw list is a
 *    second opinion about which route to set; it must go through `ReservationTargetPolicy.pick` —
 *    in practice through `chosenTargetName`.
 *
 * The point is not that any list is short but that none of them can grow silently: every new
 * entry is a second opinion about which route to set, which is exactly what this sub-project
 * removed.
 *
 * Scope and shape notes:
 * - Sources are identified by their canonical path (`module/source-root/…`), so a new file that
 *   merely copies an allow-listed *name* into another package or module does not smuggle itself
 *   into any list.
 * - `:desktop-ui` is the composition root (it wires the service into `RegistryPartialRouteReleaser`)
 *   and the declaring package `context/navigation` implements the API itself, so both are out of
 *   scope. Only non-comment source lines are examined — KDoc may name the API and the vocabulary
 *   freely.
 * - The scans are line-based heuristics. A declaration split across lines, an alias import or a
 *   reflection-level circumvention can slip past a single line; the type-annotation, generic and
 *   getter patterns below cover the spellings this codebase actually uses, and every new one has
 *   to be argued for here.
 *
 * @since Issue #1152 (SP5 — Goal 1B)
 */
@DisplayName("Target selection has one entry (allow-list tripwire, Issue #1152)")
class TargetSelectionSingleEntryTest {
	private companion object {
		/**
		 * Production source roots: the canonical repository-relative path (which is also the
		 * repository-root spelling) mapped to the alternative spelling that applies when Gradle
		 * runs the tests from a module directory; the first existing spelling wins.
		 * [SCANNED_SOURCES] fails loudly when a label resolves to nothing or scans empty.
		 */
		val SOURCE_ROOTS =
			mapOf(
				"core/src/commonMain/kotlin" to "src/commonMain/kotlin",
				"core/src/jvmMain/kotlin" to "src/jvmMain/kotlin",
				"dispatcher-agent/src/main/kotlin" to "../dispatcher-agent/src/main/kotlin"
			)

		/** The declaring/implementing package: it *is* the reservation API. */
		const val DECLARING_PACKAGE_PATH = "context/navigation"

		val TARGET_API_CALL =
			Regex(
				"""\.(reservePath|reservePathToAnyNextSemaphore|findReservationTargetCandidates""" +
					"""|isPathToAnyNextSemaphoreAvailable)\("""
			)

		/**
		 * Every way a production class stores or hands out the service: a `val`/`var` property or a
		 * parameter typed as the service, a `scope.get<…>`/`scoped<…>` lookup, and the accessor
		 * getter. Result-type mentions (`is PathReservationService.ReservationResult.X`) are *not*
		 * handle sightings and do not match.
		 */
		val SERVICE_HANDLE =
			Regex(""":\s*PathReservationService\b|<\s*PathReservationService\b|getPathReservationService\(\)""")

		/** Reads (and the one declaration) of the `candidateTargets` vocabulary. */
		val CANDIDATE_TARGET_READ = Regex("""\bcandidateTargets\b""")

		val ALLOWED_TARGET_API_CALLERS =
			setOf(
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/DefaultInterlockingFacade.kt",
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/ports/DefaultNetworkActuatorPort.kt",
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/InOutWorker.kt",
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/MultiTrainLoop.kt",
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/ShuntingLoop.kt"
			)

		val ALLOWED_SERVICE_HOLDERS =
			setOf(
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/di/CoreModule.kt",
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/DefaultSimulationContext.kt",
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/SimulationEnvironment.kt",
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/DefaultInterlockingFacade.kt",
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/ports/DefaultNetworkActuatorPort.kt",
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/InOutWorker.kt",
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/MultiTrainLoop.kt",
				"dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/RegistryPartialRouteReleaser.kt",
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/Train.kt"
			)

		val ALLOWED_CANDIDATE_TARGET_FILES =
			setOf(
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/DispatchObservation.kt",
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/ReservationTargetPolicy.kt",
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/ShuntingLoop.kt"
			)

		/**
		 * Every in-scope production file as `label/…` → its code lines, scanned once for the whole
		 * class; comment/KDoc lines are dropped here so the assertions just pattern-match.
		 */
		val SCANNED_SOURCES: Map<String, List<String>> by lazy {
			SOURCE_ROOTS
				.flatMap { (label, moduleDirSpelling) ->
					val root =
						listOf(moduleDirSpelling, label)
							.map(::File)
							.firstOrNull { it.isDirectory }
							?: error("Source root for $label does not exist under any spelling")
					root
						.walkTopDown()
						.filter { it.isFile && it.extension == "kt" }
						.filterNot { file ->
							file.relativeTo(root).invariantSeparatorsPath.contains(DECLARING_PACKAGE_PATH)
						}.map { file ->
							label + "/" + file.relativeTo(root).invariantSeparatorsPath to
								file.readLines().filter { isCode(it) }
						}.toList()
				}.toMap()
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

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("every production source root resolves and is scanned")
	fun scansEveryProductionSourceRoot() {
		for (label in SOURCE_ROOTS.keys) {
			assertThat(
				SCANNED_SOURCES.keys.count { key -> key.startsWith("$label/") },
				"files scanned under $label"
			).isGreaterThan(0)
		}
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
	@DisplayName("only allow-listed classes store a PathReservationService")
	fun onlyAllowListedClassesHoldTheService() {
		assertThat(matchingFiles(SERVICE_HANDLE), "holders of a PathReservationService handle")
			.isEqualTo(ALLOWED_SERVICE_HOLDERS)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("only allow-listed files read candidateTargets")
	fun onlyAllowListedFilesReadCandidateTargets() {
		assertThat(matchingFiles(CANDIDATE_TARGET_READ), "readers of candidateTargets")
			.isEqualTo(ALLOWED_CANDIDATE_TARGET_FILES)
	}

	/** Canonical in-scope paths whose code lines contain [pattern]. */
	private fun matchingFiles(pattern: Regex): Set<String> =
		SCANNED_SOURCES
			.filterValues { lines -> lines.any { line -> pattern.containsMatchIn(line) } }
			.keys
}
