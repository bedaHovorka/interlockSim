/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * AssertK Extensions (core module copy)
 *
 * Custom extension functions for AssertK to support property references
 * and maintain compatibility during AssertJ to AssertK migration.
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.Assert
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.support.expected
import assertk.assertions.support.show
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import java.io.File
import kotlin.reflect.KProperty0

/**
 * Extension function to support property reference assertions.
 *
 * Usage: assertThat(obj::property).isEqualTo(expectedValue)
 *
 * This allows asserting on property references directly, which is more
 * Kotlin-idiomatic than accessing the property value first.
 */
fun <T> Assert<KProperty0<T>>.isEqualTo(expected: T) =
	given { actual ->
		if (actual.get() == expected) return@given
		expected("to be equal to:${show(expected)} but was:${show(actual.get())}")
	}

/**
 * Extension function to support property reference assertions for inequality.
 *
 * Usage: assertThat(obj::property).isNotEqualTo(expectedValue)
 */
fun <T> Assert<KProperty0<T>>.isNotEqualTo(expected: T) =
	given { actual ->
		if (actual.get() != expected) return@given
		expected("to not be equal to:${show(expected)}")
	}

/**
 * Extension function to support property reference assertions for null checks.
 *
 * Usage: assertThat(obj::property).isNull()
 */
fun <T> Assert<KProperty0<T?>>.isNull() =
	given { actual ->
		if (actual.get() == null) return@given
		expected("to be null but was:${show(actual.get())}")
	}

/**
 * Extension function to support property reference assertions for non-null checks.
 *
 * Usage: assertThat(obj::property).isNotNull()
 */
fun <T> Assert<KProperty0<T?>>.isNotNull() =
	given { actual ->
		if (actual.get() != null) return@given
		expected("to not be null")
	}

/**
 * Extension function to assert that a block of code executes without throwing an exception.
 *
 * Usage: assertThatCode { /* code */ }.doesNotThrowAnyException()
 */
fun assertThatCode(block: () -> Unit): Assert<Result<Unit>> {
	val result = runCatching { block() }
	return assertk.assertThat(result)
}

/**
 * Extension function to assert that a Result represents success (no exception).
 *
 * Usage: assertThatCode { /* code */ }.doesNotThrowAnyException()
 */
fun Assert<Result<Unit>>.doesNotThrowAnyException() =
	given { actual ->
		if (actual.isSuccess) return@given
		val exception = actual.exceptionOrNull()
		expected("not to throw any exception but threw:${show(exception)}")
	}

/**
 * Extension function to assert that a File exists.
 *
 * Usage: assertThat(file).exists()
 */
fun Assert<File>.exists(): Assert<File> =
	apply {
		given { actual ->
			if (actual.exists()) return@given
			expected("to exist but was:${show(actual.absolutePath)}")
		}
	}

/**
 * Extension function to assert that a File is a file (not a directory).
 *
 * Usage: assertThat(file).isFile()
 */
fun Assert<File>.isFile(): Assert<File> =
	apply {
		given { actual ->
			if (actual.isFile) return@given
			expected("to be a file but was:${show(actual.absolutePath)}")
		}
	}

/**
 * Extension function to assert that a String (nullable) contains any of the given substrings.
 *
 * Usage: assertThat(string).containsAnyOf("sub1", "sub2")
 */
fun Assert<String?>.containsAnyOf(vararg substrings: String) =
	given { actual ->
		if (actual != null && substrings.any { actual.contains(it) }) return@given
		expected("to contain any of:${show(substrings.toList())} but was:${show(actual)}")
	}

/**
 * Extension function for Set element containment check with explicit typing.
 */
@JvmName("containsElementSet")
fun <T> Assert<Set<T>>.containsElement(element: T): Assert<Set<T>> =
	apply {
		given { actual ->
			if (element in actual) return@given
			expected("to contain:${show(element)} but was:${show(actual)}")
		}
	}

/**
 * Extension function for Collection element containment check with explicit typing.
 */
@JvmName("containsElementCollection")
fun <T> Assert<Collection<T>>.containsElement(element: T): Assert<Collection<T>> =
	apply {
		given { actual ->
			if (element in actual) return@given
			expected("to contain:${show(element)} but was:${show(actual)}")
		}
	}

/**
 * Extension function for Set to explicitly resolve containsExactly.
 */
@JvmName("containsExactlySet")
fun <T> Assert<Set<T>>.containsExactly(vararg elements: T): Assert<Set<T>> =
	apply {
		given { actual ->
			val expectedSet = elements.toSet()
			if (actual == expectedSet) return@given
			expected("to contain exactly:${show(expectedSet)} but was:${show(actual)}")
		}
	}

/**
 * Extension function for Collection to explicitly resolve containsExactly.
 */
@JvmName("containsExactlyCollection")
fun <T> Assert<Collection<T>>.containsExactly(vararg elements: T): Assert<Collection<T>> =
	apply {
		given { actual ->
			val expectedList = elements.toList()
			if (actual.size == expectedList.size && actual.containsAll(expectedList)) return@given
			expected("to contain exactly:${show(expectedList)} but was:${show(actual)}")
		}
	}

/**
 * Extension function to assert that a collection is not empty.
 */
fun <T> Assert<Collection<T>>.isNotEmpty(): Assert<Collection<T>> =
	apply {
		given { actual ->
			if (actual.isNotEmpty()) return@given
			expected("to not be empty but was empty")
		}
	}

/**
 * Chainable hasSize extension for Collection that returns Assert for method chaining.
 */
@JvmName("hasSizeChainableCollection")
fun <T> Assert<Collection<T>>.hasSizeChainable(size: Int): Assert<Collection<T>> =
	apply {
		given { actual ->
			if (actual.size == size) return@given
			expected("to have size:${show(size)} but was:${show(actual.size)}")
		}
	}

/**
 * Chainable hasSize extension for Map that returns Assert for method chaining.
 */
@JvmName("hasSizeChainableMap")
fun <K, V> Assert<Map<K, V>>.hasSizeChainable(size: Int): Assert<Map<K, V>> =
	apply {
		given { actual ->
			if (actual.size == size) return@given
			expected("to have size:${show(size)} but was:${show(actual.size)}")
		}
	}

/**
 * Chainable hasSize extension for Set that returns Assert for method chaining.
 */
@JvmName("hasSizeChainableSet")
fun <T> Assert<Set<T>>.hasSizeChainable(size: Int): Assert<Set<T>> =
	apply {
		given { actual ->
			if (actual.size == size) return@given
			expected("to have size:${show(size)} but was:${show(actual.size)}")
		}
	}

/**
 * Extension function to assert that a collection contains at least one null element.
 */
@JvmName("containsNullCollection")
fun <T> Assert<Collection<T?>>.containsNull(): Assert<Collection<T?>> =
	apply {
		given { actual ->
			if (actual.any { it == null }) return@given
			expected("to contain null but was:${show(actual)}")
		}
	}

/**
 * Asserts that an `env.errorStop` throwable carrying [fragment] in its message was captured, and
 * returns it.
 *
 * The bounded-retry regression tests (mid-journey, #905, #943) all capture the throwables passed to
 * `env.errorStop` into a `CopyOnWriteArrayList` and then repeat the same triplet: look the
 * throwable up by message fragment, assert it is not null, assert its message contains the
 * fragment. This helper is that triplet, with the failure message listing every message actually
 * captured so a miss is diagnosable without re-running the simulation.
 */
fun assertCapturedErrorStop(
	capturedErrors: Collection<Throwable>,
	fragment: String
): Throwable =
	capturedErrors.firstOrNull { it.message?.contains(fragment) == true }
		?: throw AssertionError(
			"expected a captured errorStop whose message contains ${show(fragment)}, " +
				"but captured messages were:${show(capturedErrors.map { it.message })}"
		)

/**
 * Asserts that [result] is a successful reservation and returns it already narrowed.
 *
 * Every reservation test needs the concrete `Success` to read `reservedBlocks`, so each site
 * carried the same two lines — an `isInstanceOf<…Success>()` assertion followed by an
 * `as …Success` cast of the very same value (Issue #955, cluster N4). One call replaces both, and
 * a failure now names the actual result instead of raising a `ClassCastException`.
 */
fun assertReservationSuccess(
	result: PathReservationService.ReservationResult
): PathReservationService.ReservationResult.Success =
	result as? PathReservationService.ReservationResult.Success
		?: throw AssertionError("expected a successful reservation but was:${show(result)}")

/**
 * Asserts that every block in [blocks] is RESERVED, held by [trainName], and reserved from
 * [reservedFrom].
 *
 * These three per-block assertions always travel together — a block that is RESERVED but owned by
 * the wrong train, or reserved from the wrong end, is exactly the bug this trio catches (Issue
 * #955, cluster N4). The failure message names the offending block.
 */
fun assertReservedBlocks(
	blocks: Collection<DynamicTrackBlock>,
	trainName: String,
	reservedFrom: PathSeparator
) {
	blocks.forEach { block ->
		assertThat(block.getState(), "state of ${block.name}").isEqualTo(TrackFacility.State.RESERVED)
		assertThat(block.reservedFrom, "reservedFrom of ${block.name}").isEqualTo(reservedFrom)
		assertThat(block.trainName, "trainName of ${block.name}").isEqualTo(trainName)
	}
}
