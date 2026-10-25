/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Wall-clock tripwire for the reservation kernel (Issue #1174): nothing under
 * `core/src/commonMain/.../context/navigation` may read wall-clock time. Reservation expiry follows
 * simulation progress only -- registration timestamps and the TTL are simulation time, read through
 * the registry's `simTimeSource` / `currentSimulationTime()` -- so a run is reproducible however fast
 * the host executes it, and a paused clock (the #849 LLM emission window) never ages a reservation.
 *
 * detekt's `ForbiddenMethodCall` is off and the commonMain purity gate only catches `System.*`, so
 * neither sees `currentTimeMillisKMP`, `Clock.System`, `TimeSource` or `measureTime`; this line-based
 * scan does. Comment and KDoc lines are skipped, so documentation may still name them.
 */
class NavigationWallClockTripwireTest {
	private companion object {
		/** The package, by its repository-root and module-directory spellings; the first existing wins. */
		val ROOT_SPELLINGS =
			listOf(
				"src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation",
				"core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation"
			)

		val WALL_CLOCK_READ =
			Regex("""currentTimeMillisKMP|System\.currentTimeMillis|Clock\.System|\bTimeSource\b|\bmeasureTime""")

		fun isCode(line: String): Boolean {
			val trimmed = line.trim()
			return trimmed.isNotEmpty() &&
				!trimmed.startsWith("//") &&
				!trimmed.startsWith("*") &&
				!trimmed.startsWith("/*")
		}
	}

	private val navigationSources: List<File> by lazy {
		val root =
			ROOT_SPELLINGS.map(::File).firstOrNull { it.isDirectory }
				?: error("context/navigation sources not found under any of $ROOT_SPELLINGS")
		root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
	}

	@Test
	fun `no navigation source reads wall-clock time`() {
		val hits =
			navigationSources.flatMap { file ->
				file
					.readLines()
					.withIndex()
					.filter { (_, line) -> isCode(line) && WALL_CLOCK_READ.containsMatchIn(line) }
					.map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
			}

		assertThat(hits).isEmpty()
	}

	@Test
	fun `the scan is not vacuous`() {
		// The package really is scanned, and the registry and service the expiry lives in are in it.
		assertThat(navigationSources.size).isGreaterThan(10)
		assertThat(navigationSources.any { it.name == "PathReservationRegistry.kt" }).isTrue()
		assertThat(navigationSources.any { it.name == "DefaultPathReservationService.kt" }).isTrue()
		// Every forbidden spelling is caught, and the registry's own sim clock is not.
		listOf(
			"val t = currentTimeMillisKMP()",
			"val t = System.currentTimeMillis()",
			"val t = Clock.System.now()",
			"val mark = TimeSource.Monotonic.markNow()",
			"val d = measureTime { work() }",
			"val d = measureTimeMillis { work() }"
		).forEach { assertThat(WALL_CLOCK_READ.containsMatchIn(it)).isTrue() }
		assertThat(WALL_CLOCK_READ.containsMatchIn("private val simTimeSource: () -> Double")).isFalse()
	}
}
