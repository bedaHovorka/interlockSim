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
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.SourceScan
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
 * scan does. It reads code through [SourceScan.codeLines] (Issue #1379): comments, KDoc, trailing
 * comments and string literals are stripped, so documentation may still name them.
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

		/**
		 * The 0-based indices of the [lines] (one file) whose code reads wall-clock time; comments are
		 * stripped by [SourceScan.codeLines], so a trailing comment or a block comment never counts.
		 */
		fun wallClockReads(lines: List<String>): List<Int> =
			SourceScan
				.codeLines(lines)
				.withIndex()
				.filter { (_, code) -> WALL_CLOCK_READ.containsMatchIn(code) }
				.map { it.index }
	}

	private val navigationSources: List<File> by lazy {
		SourceScan.kotlinFiles(SourceScan.directory(*ROOT_SPELLINGS.toTypedArray()))
	}

	@Test
	fun `no navigation source reads wall-clock time`() {
		val hits =
			navigationSources.flatMap { file ->
				val lines = file.readLines()
				wallClockReads(lines).map { index -> "${file.name}:${index + 1}: ${lines[index].trim()}" }
			}

		assertThat(hits).isEmpty()
	}

	@Test
	fun `the scan is not vacuous`() {
		// The package really is scanned, and the registry the expiry queries, the service that
		// delegates to it, and the expiry policy class itself are all in it.
		assertThat(navigationSources.size).isGreaterThan(10)
		assertThat(navigationSources.any { it.name == "PathReservationRegistry.kt" }).isTrue()
		assertThat(navigationSources.any { it.name == "DefaultPathReservationService.kt" }).isTrue()
		assertThat(navigationSources.any { it.name == "DefaultReservationExpiry.kt" }).isTrue()
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

	@Test
	fun `a wall-clock name in a trailing comment does not count as a read`() {
		assertThat(wallClockReads(listOf("val t = simTime() // not System.currentTimeMillis"))).isEmpty()
	}

	@Test
	fun `a wall-clock read inside a block comment does not count`() {
		val lines = listOf("/* Former code:", "\tval t = System.currentTimeMillis()", "*/", "val t = simTime()")

		assertThat(wallClockReads(lines)).isEmpty()
	}

	@Test
	fun `a real wall-clock read is still caught, with or without a trailing comment`() {
		val lines =
			listOf(
				"/** KDoc may name Clock.System. */",
				"val t = System.currentTimeMillis() // why",
				"val mark = TimeSource.Monotonic.markNow()"
			)

		assertThat(wallClockReads(lines)).containsExactly(1, 2)
	}
}
