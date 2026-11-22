/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP7 (Issue #1154): wait-for graph and persistence rule of the deadlock watchdog.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test

class DeadlockWatchdogTest {
	private fun graph(vararg edges: Pair<String, String>): WaitForGraph =
		WaitForGraph().also { g -> edges.forEach { (waiter, holder) -> g.addEdge(waiter, holder) } }

	@Test
	fun chainHasNoCycle() {
		assertThat(graph("a" to "b", "b" to "c").findCycle(), name = "cycle of a chain").isNull()
	}

	@Test
	fun selfWaitIsNotACycle() {
		assertThat(graph("a" to "a").findCycle(), name = "cycle of a self-wait").isNull()
	}

	@Test
	fun twoTrainsWaitingForEachOtherAreACycle() {
		val cycle = graph("a" to "b", "b" to "a").findCycle()
		assertThat(cycle.orEmpty().toSet(), name = "cycle members").containsOnly("a", "b")
	}

	@Test
	fun cycleBehindATailIsFoundWithoutTheTail() {
		val cycle = graph("t" to "a", "a" to "b", "b" to "c", "c" to "a").findCycle()
		assertThat(cycle.orEmpty().toSet(), name = "cycle members").containsOnly("a", "b", "c")
	}

	@Test
	fun cycleMustPersistForTheConfiguredTicks() {
		val watchdog = DeadlockWatchdog(persistTicks = 3)
		val cycle = listOf("a", "b")
		assertThat(watchdog.observe(2.0, cycle), name = "tick 1").isNull()
		assertThat(watchdog.observe(4.0, cycle), name = "tick 2").isNull()
		assertThat(watchdog.observe(6.0, cycle), name = "tick 3").isNotNull()
	}

	@Test
	fun aCycleSeenAtTheSameClockValueDoesNotCount() {
		val watchdog = DeadlockWatchdog(persistTicks = 2)
		val cycle = listOf("a", "b")
		assertThat(watchdog.observe(2.0, cycle), name = "first observation").isNull()
		assertThat(watchdog.observe(2.0, cycle), name = "same clock value (frozen clock is not ours)").isNull()
		assertThat(watchdog.observe(4.0, cycle), name = "clock advanced").isNotNull()
	}

	@Test
	fun noCycleOrADifferentCycleRestartsTheCount() {
		val watchdog = DeadlockWatchdog(persistTicks = 2)
		assertThat(watchdog.observe(2.0, listOf("a", "b")), name = "first").isNull()
		assertThat(watchdog.observe(4.0, null), name = "cycle gone").isNull()
		assertThat(watchdog.observe(6.0, listOf("a", "b")), name = "cycle again, count restarted").isNull()
		assertThat(watchdog.observe(8.0, listOf("b", "a", "c")), name = "different cycle").isNull()
		assertThat(
			watchdog.observe(10.0, listOf("c", "b", "a"))?.toSet(),
			name = "same members in another order persist"
		).isEqualTo(setOf("a", "b", "c"))
	}

	@Test
	fun alternatingCyclesStillReportAfterTheChurnBound() {
		// A churn livelock reports a different member set per tick, so the same-member rule restarts
		// on every tick; a permanent stall of two alternating cycles must still be reported, at the
		// churn bound (DeadlockWatchdog KDoc): `persistTicks * 10` ticks with ANY cycle present.
		val watchdog = DeadlockWatchdog(persistTicks = 2)
		val churnBound = 2 * 10
		val first = listOf("a", "b")
		val second = listOf("c", "d")
		var time = 2.0
		for (tick in 1 until churnBound) {
			val cycle = if (tick % 2 == 1) first else second
			assertThat(watchdog.observe(time, cycle), name = "no report below the churn bound (tick $tick)")
				.isNull()
			time += 2.0
		}
		assertThat(watchdog.observe(time, first), name = "report at the churn bound").isNotNull()
	}
}
