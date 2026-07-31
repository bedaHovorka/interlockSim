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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import cz.vutbr.fit.interlockSim.dispatcher.CommandId
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.ValidationVerdict
import cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Acceptance-criteria tests for [TickRingBuffer] (SP2c.7, Issue #830).
 *
 * @since Issue #830 (SP2c.7 — Goal 10 ring buffer)
 */
@DisplayName("SP2c.7 TickRingBuffer — acceptance criteria (#830)")
class TickRingBufferTest {
	private fun emptyRecord(tick: Long): TickRecord =
		TickRecord(
			tick = tick,
			simTime = tick.toDouble(),
			stateDigest = "0".repeat(64),
			actions = emptyList(),
			verdicts = emptyList(),
			outcomes = emptyList()
		)

	// ── AC: capacity bounds ───────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Capacity — pushing N records retains exactly the last N, oldest first")
	inner class CapacityBounds {
		@Test
		@DisplayName("pushing 10 records into capacity=3 buffer leaves exactly 3 records")
		fun pushingTenLeavesThree() {
			val buf = TickRingBuffer(capacity = 3)
			repeat(10) { i -> buf.push(emptyRecord(i.toLong())) }
			assertThat(buf.snapshot().size).isEqualTo(3)
		}

		@Test
		@DisplayName("after pushing 10, the retained records are ticks 7, 8, 9 (oldest first)")
		fun retainsOldestFirst() {
			val buf = TickRingBuffer(capacity = 3)
			repeat(10) { i -> buf.push(emptyRecord(i.toLong())) }
			val snap = buf.snapshot()
			assertThat(snap[0].tick).isEqualTo(7L)
			assertThat(snap[1].tick).isEqualTo(8L)
			assertThat(snap[2].tick).isEqualTo(9L)
		}

		@Test
		@DisplayName("pushing exactly capacity records retains all of them")
		fun pushingCapacityRetainsAll() {
			val buf = TickRingBuffer(capacity = 3)
			repeat(3) { i -> buf.push(emptyRecord(i.toLong())) }
			assertThat(buf.snapshot().size).isEqualTo(3)
		}

		@Test
		@DisplayName("snapshot is oldest-first: tick 0 before tick 1 before tick 2")
		fun snapshotOldestFirst() {
			val buf = TickRingBuffer(capacity = 3)
			buf.push(emptyRecord(0L))
			buf.push(emptyRecord(1L))
			buf.push(emptyRecord(2L))
			val snap = buf.snapshot()
			assertThat(snap[0].tick).isEqualTo(0L)
			assertThat(snap[1].tick).isEqualTo(1L)
			assertThat(snap[2].tick).isEqualTo(2L)
		}

		@Test
		@DisplayName("snapshot before any push is empty")
		fun emptyBeforeFirstPush() {
			val buf = TickRingBuffer(capacity = 3)
			assertThat(buf.snapshot()).isEmpty()
		}
	}

	// ── AC: clear ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("clear — empties the buffer")
	inner class Clear {
		@Test
		@DisplayName("clear after 3 pushes returns empty snapshot")
		fun clearEmptiesBuffer() {
			val buf = TickRingBuffer(capacity = 3)
			repeat(3) { i -> buf.push(emptyRecord(i.toLong())) }
			buf.clear()
			assertThat(buf.snapshot()).isEmpty()
		}

		@Test
		@DisplayName("push after clear works normally")
		fun pushAfterClear() {
			val buf = TickRingBuffer(capacity = 3)
			repeat(3) { i -> buf.push(emptyRecord(i.toLong())) }
			buf.clear()
			buf.push(emptyRecord(99L))
			val snap = buf.snapshot()
			assertThat(snap.size).isEqualTo(1)
			assertThat(snap[0].tick).isEqualTo(99L)
		}
	}

	// ── AC: configurable capacity ─────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Configurable capacity — SP2c.11 sweep")
	inner class ConfigurableCapacity {
		@Test
		@DisplayName("capacity=1 retains only the latest record")
		fun capacityOne() {
			val buf = TickRingBuffer(capacity = 1)
			buf.push(emptyRecord(0L))
			buf.push(emptyRecord(1L))
			buf.push(emptyRecord(2L))
			val snap = buf.snapshot()
			assertThat(snap.size).isEqualTo(1)
			assertThat(snap[0].tick).isEqualTo(2L)
		}

		@Test
		@DisplayName("capacity=5 retains up to 5 records")
		fun capacityFive() {
			val buf = TickRingBuffer(capacity = 5)
			repeat(10) { i -> buf.push(emptyRecord(i.toLong())) }
			assertThat(buf.snapshot().size).isEqualTo(5)
		}

		@Test
		@DisplayName("default capacity is 3")
		fun defaultCapacityIsThree() {
			assertThat(TickRingBuffer().capacity).isEqualTo(3)
		}
	}

	// ── AC: snapshot immutability ─────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Snapshot immutability — subsequent pushes don't affect prior snapshots")
	inner class SnapshotImmutability {
		@Test
		@DisplayName("snapshot taken before push is not affected by subsequent push")
		fun snapshotIsImmutable() {
			val buf = TickRingBuffer(capacity = 3)
			buf.push(emptyRecord(0L))
			buf.push(emptyRecord(1L))
			val snap1 = buf.snapshot()
			buf.push(emptyRecord(2L))
			// snap1 should still have 2 elements
			assertThat(snap1.size).isEqualTo(2)
			assertThat(snap1[0].tick).isEqualTo(0L)
		}
	}

	// ── AC: rendered ring-buffer size ceiling ─────────────────────────────────────────────

	@Nested
	@DisplayName("Rendered size ceiling — RECENT TICKS block has hard character ceiling")
	inner class RenderedSizeCeiling {
		/**
		 * Maximum characters for the RECENT TICKS prompt section.
		 *
		 * With N=3 records, each record rendering: ~100 chars (tick, t, digest, actions, outcomes).
		 * Ceiling 600 chars = 3 × 200 chars per record (generous). The assertion proves the section
		 * cannot grow unbounded regardless of action count.
		 */
		private val maxRecentTicksChars = 600

		@Test
		@DisplayName("rendered RECENT TICKS block stays within char ceiling for full 3-record buffer")
		fun renderedRecentTicksBlockWithinCharCeiling() {
			val buf = TickRingBuffer(capacity = 3)
			// Push 3 maximally populated records: 3 distinct AttributedActions each (per-tick
			// action limit is 3) paired with ValidationVerdict.Valid, plus a non-empty outcomes
			// list to exercise the `out=[…]` suffix — the worst case for the section length.
			repeat(3) { i ->
				val tick = (38 + i).toLong()
				val actions =
					listOf(
						AttributedAction(CommandId(tick * 1000), tick, DispatchAction.RequestRoute("T-1", "A", "B")),
						AttributedAction(CommandId(tick * 1000 + 1), tick, DispatchAction.ApproveTrain("T-2")),
						AttributedAction(CommandId(tick * 1000 + 2), tick, DispatchAction.CancelRoute("T-3"))
					)
				val verdicts =
					listOf(
						ValidationVerdict.Valid,
						ValidationVerdict.Valid,
						ValidationVerdict.Valid
					)
				val outcomes =
					listOf(
						AppliedOutcome.Reserved(
							trainId = "T-1",
							fromEndpointName = "A",
							toEndpointName = "B",
							blocksCount = 2,
							id = CommandId(tick * 1000),
							tickIndex = tick
						)
					)
				buf.push(
					TickRecord(
						tick = tick,
						simTime = tick.toDouble(),
						stateDigest = "a1b2c3d4".repeat(8),
						actions = actions,
						verdicts = verdicts,
						outcomes = outcomes
					)
				)
			}
			// Render from buf.snapshot() using the fixture observation/previous/workingMemory/
			// affordances for the non-history slots.
			val ctx =
				RenderContext(
					observation = RendererFixtures.observationTick41,
					previous = RendererFixtures.observationTick40,
					history = buf.snapshot(),
					workingMemory = RendererFixtures.workingMemory,
					affordances = RendererFixtures.affordances
				)
			val output = CompactTextRenderer().render(ctx)
			val recentTicksStart = output.indexOf("=== RECENT TICKS ===")
			val workingMemStart = output.indexOf("=== WORKING MEMORY ===")
			val recentTicksSection = output.substring(recentTicksStart, workingMemStart)
			assertThat(recentTicksSection.length).isLessThan(maxRecentTicksChars)
		}
	}
}
