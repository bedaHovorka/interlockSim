/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for the cached AbstractPath.length() (Issue #962)
 */
package cz.vutbr.fit.interlockSim.objects.paths

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.lang.reflect.Modifier

/**
 * [AbstractPath.length] caches the element-length sum (Issue #962). The path is mutated during a
 * run — the front consumes `pathToSemaphore` with `removeFirst` — so the cache is only correct if
 * every mutator of [ArrayPath] clears it, including the ones `by deque` would otherwise delegate
 * straight to the deque and the ones that mutate through an iterator.
 *
 * Each case primes the cache, mutates, and compares [AbstractPath.length] with a fresh sum over
 * the elements. [every public mutator of ArrayPath has an invalidation case] is the guard: it
 * fails as soon as ArrayPath's collection or Path surface gains a method this test has not
 * classified, so a new mutator cannot slip in without a case of its own.
 */
@DisplayName("AbstractPath.length() cache invalidation (Issue #962)")
class PathLengthCacheTest : KoinTestBase() {
	private lateinit var mockContext: MockSimulationContext

	@BeforeEach
	fun setUp() {
		mockContext = createMockSimulationContext()
	}

	/** A path element with a fixed length that counts how often its length is read. */
	private class LengthElement(
		private val length: Double
	) : PathElement {
		var reads: Int = 0
			private set

		override fun contributeToPathLength(): Double {
			reads++
			return length
		}

		override fun toString(): String = "LengthElement($length)"
	}

	private val first = LengthElement(FIRST_LENGTH)
	private val second = LengthElement(SECOND_LENGTH)
	private val third = LengthElement(THIRD_LENGTH)
	private val extra = LengthElement(EXTRA_LENGTH)

	/** The elements a [Mutation] indexes: `[0]`..`[2]` build the primed path, `[3]` is the extra one. */
	private val elements = listOf(first, second, third, extra)

	private fun pathOf(vararg elements: PathElement): ArrayPath =
		ArrayPath(mockContext).apply { elements.forEach { addLast(it) } }

	private fun freshSum(path: Path): Double = path.toList().sumOf { it.contributeToPathLength() }

	/** One mutation of an [ArrayPath], named after the public method it goes through. */
	class Mutation(
		val method: String,
		val mutate: (ArrayPath, List<PathElement>) -> Unit
	) {
		override fun toString(): String = method
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("mutations")
	fun `length after a mutation equals a fresh element sum`(mutation: Mutation) {
		val path = pathOf(first, second, third)
		assertThat(path.length(), name = "primed length").isEqualTo(FIRST_LENGTH + SECOND_LENGTH + THIRD_LENGTH)

		mutation.mutate(path, elements)

		val expected = freshSum(path)
		assertThat(path.length(), name = "length after ${mutation.method}").isEqualTo(expected)
	}

	@Test
	fun `every mutation case changes the element sum`() {
		// A case that leaves the sum unchanged would pass against a stale cache and prove nothing.
		val primed = FIRST_LENGTH + SECOND_LENGTH + THIRD_LENGTH
		val unchanged =
			mutations().filter { mutation ->
				val path = pathOf(first, second, third)
				mutation.mutate(path, elements)
				freshSum(path) == primed
			}
		assertThat(unchanged.map { it.method }).isEmpty()
	}

	@Test
	fun `every public mutator of ArrayPath has an invalidation case`() {
		val surface =
			(
				Class.forName("java.util.Collection").methods.asList() +
					Path::class.java.declaredMethods.asList() +
					listOf(AbstractPath::class.java, ArrayPath::class.java).flatMap { type ->
						type.declaredMethods.filter { Modifier.isPublic(it.modifiers) }
					}
			).filterNot { it.isSynthetic }
				.map { it.name }
				.toSet()
		val unclassified = surface - MUTATORS - READ_ONLY
		assertThat(unclassified, name = "methods neither a tested mutator nor known read-only").isEmpty()
		assertThat(mutations().map { it.method }.toSet(), name = "mutators with a case").isEqualTo(MUTATORS)
	}

	@Test
	fun `length is computed once while the path is unchanged`() {
		val path = pathOf(first, second, third)
		repeat(REPEATED_READS) { path.length() }
		assertThat(first.reads, name = "element length reads").isEqualTo(1)

		path.removeFirst()
		repeat(REPEATED_READS) { path.length() }
		assertThat(second.reads, name = "element length reads after one mutation").isEqualTo(2)
	}

	@Test
	fun `a mutation that fails or changes nothing leaves the length correct`() {
		// Outside mutations(): these calls leave the element sum unchanged, which that list forbids.
		val empty = ArrayPath(mockContext)
		assertThat(empty.length(), name = "primed empty length").isEqualTo(0.0)
		assertFailure { empty.removeFirst() }.isInstanceOf<NoSuchElementException>()
		assertThat(empty.length(), name = "length after a failed removeFirst").isEqualTo(0.0)
		empty.addLast(extra)
		assertThat(empty.length(), name = "length after addLast on the formerly empty path").isEqualTo(EXTRA_LENGTH)

		val path = pathOf(first, second)
		assertThat(path.length(), name = "primed length").isEqualTo(FIRST_LENGTH + SECOND_LENGTH)
		assertThat(path.remove(extra), name = "remove of an absent element").isFalse()
		assertThat(path.length(), name = "length after removing an absent element").isEqualTo(freshSum(path))
	}

	@Test
	fun `reversePath gives a copy whose mutations leave the original cache alone`() {
		val original = pathOf(first, second, third)
		val primed = original.length()
		val reversed = original.reversePath()
		assertThat(reversed.length(), name = "reversed length").isEqualTo(primed)

		reversed.removeFirst()
		assertThat(reversed.length(), name = "reversed length after removeFirst").isEqualTo(freshSum(reversed))
		assertThat(original.length(), name = "original length after mutating the copy").isEqualTo(primed)
		assertThat(freshSum(original), name = "original element sum").isEqualTo(primed)
	}

	@Test
	fun `TransitionAwarePath never reports a stale length`() {
		val delegate = pathOf(first, second, third)
		val wrapper = TransitionAwarePath(delegate, mockk<TrackSection>(), mockk<TrackSection>())
		assertThat(wrapper.length()).isEqualTo(freshSum(delegate))

		wrapper.addLast(extra)
		assertThat(wrapper.length(), name = "after a mutation through the wrapper").isEqualTo(freshSum(delegate))

		delegate.removeFirst()
		assertThat(wrapper.length(), name = "after a mutation of the delegate").isEqualTo(freshSum(delegate))

		wrapper.iterator().apply {
			next()
			remove()
		}
		assertThat(wrapper.length(), name = "after an iterator remove through the wrapper").isEqualTo(freshSum(delegate))
	}

	companion object {
		private const val FIRST_LENGTH = 100.0
		private const val SECOND_LENGTH = 20.0
		private const val THIRD_LENGTH = 3.0
		private const val EXTRA_LENGTH = 0.5
		private const val REPEATED_READS = 5

		/** Public methods that change the element sequence; each needs a case in [mutations]. */
		private val MUTATORS =
			setOf(
				"add",
				"addAll",
				"remove",
				"removeAll",
				"retainAll",
				"clear",
				"removeIf",
				"iterator",
				"descendingIterator",
				"addFirst",
				"addLast",
				"removeFirst"
			)

		/** Public methods of the same surface that do not change the element sequence. */
		private val READ_ONLY =
			setOf(
				"size",
				"getSize",
				"isEmpty",
				"contains",
				"containsAll",
				"toArray",
				"stream",
				"parallelStream",
				"spliterator",
				"forEach",
				"equals",
				"hashCode",
				"toString",
				"getLastPathSemaphore",
				"maxSpeed",
				"reversePath",
				"getFirst",
				"getLast",
				"getNext",
				"equalsWithElements",
				// AbstractPath: reservation and occupancy work over the elements, never on the sequence
				"length",
				"ends",
				"isFreeFrom",
				"isSetUpPath",
				"setUpPath",
				"cancelPathSetup",
				"getContext",
				"getState",
				"enter",
				"leave",
				"getTrackOccupant"
			)

		@JvmStatic
		fun mutations(): List<Mutation> =
			listOf(
				Mutation("add") { path, e -> path.add(e[3]) },
				Mutation("addAll") { path, e -> path.addAll(listOf(e[3], e[3])) },
				Mutation("remove") { path, e -> path.remove(e[1]) },
				Mutation("removeAll") { path, e -> path.removeAll(listOf(e[0])) },
				Mutation("retainAll") { path, e -> path.retainAll(listOf(e[0], e[2])) },
				Mutation("clear") { path, _ -> path.clear() },
				Mutation("removeIf") { path, e -> path.removeIf { it === e[2] } },
				Mutation("iterator") { path, _ ->
					val iterator = path.iterator()
					iterator.next()
					iterator.remove()
				},
				Mutation("descendingIterator") { path, _ ->
					val iterator = path.descendingIterator() as MutableIterator<PathElement>
					iterator.next()
					iterator.remove()
				},
				Mutation("addFirst") { path, e -> path.addFirst(e[3]) },
				Mutation("addLast") { path, e -> path.addLast(e[3]) },
				Mutation("removeFirst") { path, _ -> path.removeFirst() }
			)
	}
}
