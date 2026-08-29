/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Builds a counter map holding one zeroed [AtomicLong] per entry of an enum, keyed by the enum
 * entry itself.
 *
 * Every counting component in this package needs the same structure: a map that is fully
 * populated at construction time so the hot path only ever calls [AtomicLong.incrementAndGet]
 * and never mutates the map itself. That keeps the writer thread allocation-free and lets a
 * snapshot be read from any other thread without synchronization. The idiom was hand-written
 * eight times across [DefaultDispatcherRunRecorder], [ActionOutcomeAggregator] and
 * [MeasuringPlanAdapter] before Issue #713 Task 10 extracted it here.
 *
 * @param entries The enum's entries, e.g. `TickOutcome.entries`.
 * @return A [ConcurrentHashMap] with one `AtomicLong(0)` per entry.
 *
 * @since Issue #713 (Task 10 — compilation warnings elimination round)
 */
internal fun <E : Enum<E>> concurrentEnumCounters(entries: List<E>): ConcurrentHashMap<E, AtomicLong> =
	concurrentEnumCounters(entries) { it }

/**
 * Builds a counter map holding one zeroed [AtomicLong] per entry of an enum, keyed by whatever
 * [key] derives from each entry.
 *
 * The key-deriving overload exists for [DefaultDispatcherRunRecorder], whose counters are keyed
 * by `entry.name`: its snapshot ([DispatcherRunSnapshot]) is JSON-serialized and therefore holds
 * `Map<String, Long>` rather than enum-keyed maps.
 *
 * @param entries The enum's entries, e.g. `TickOutcome.entries`.
 * @param key Derives the map key from an entry — pass `{ it.name }` for a string-keyed map.
 * @return A [ConcurrentHashMap] with one `AtomicLong(0)` per entry.
 *
 * @since Issue #713 (Task 10 — compilation warnings elimination round)
 */
internal fun <E : Enum<E>, K : Any> concurrentEnumCounters(
	entries: List<E>,
	key: (E) -> K
): ConcurrentHashMap<K, AtomicLong> =
	ConcurrentHashMap<K, AtomicLong>().also { map ->
		entries.forEach { entry -> map[key(entry)] = AtomicLong(0L) }
	}
