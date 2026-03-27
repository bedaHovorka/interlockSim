/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * AssertK Extensions (commonTest module)
 *
 * Cross-platform extension functions for AssertK assertions.
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.Assert
import assertk.assertions.support.expected
import assertk.assertions.support.show

/**
 * Extension function to add a description/message to an assertion.
 *
 * Note: AssertK doesn't support post-hoc message addition like AssertJ.
 * The message parameter is effectively ignored here.
 * For proper message support, use: assertThat(value, name = "message").assertion()
 */
fun <T> Assert<T>.withMessage(message: String): Assert<T> {
	return this
}

/**
 * Extension function to assert on a lambda/block - compatible with both Unit and non-Unit returns.
 *
 * Usage: assertThat { someCode() }.isFailure()
 */
inline fun <R> assertThat(crossinline block: () -> R): Assert<Result<R>> {
	val result = runCatching { block() }
	return assertk.assertThat(result)
}

/**
 * Extension function for message content assertions.
 *
 * Usage: assertThat { code }.isFailure().hasMessageContaining("expected text")
 */
fun <R> Assert<Result<R>>.hasMessageContaining(substring: String): Assert<Result<R>> =
	apply {
		given { actual ->
			val message = actual.exceptionOrNull()?.message
			if (message != null && message.contains(substring)) return@given
			expected("exception message to contain:${show(substring)} but was:${show(message)}")
		}
	}

/**
 * Extension function for message content assertions on Throwable types.
 *
 * @JvmName is required: both overloads erase to the same JVM signature
 * `hasMessageContaining(Assert, String): Assert` because extension receivers
 * become the first parameter and generics are erased.
 */
@kotlin.jvm.JvmName("hasMessageContainingThrowable")
fun Assert<Throwable>.hasMessageContaining(substring: String): Assert<Throwable> =
	apply {
		given { actual ->
			val message = actual.message
			if (message != null && message.contains(substring)) return@given
			expected("exception message to contain:${show(substring)} but was:${show(message)}")
		}
	}

/**
 * Extension function to assert that a Map contains a specific key-value entry.
 */
fun <K, V> Assert<Map<K, V>>.containsEntry(
	key: K,
	value: V
): Assert<Map<K, V>> =
	apply {
		given { actual ->
			val actualValue = actual[key]
			if (actualValue == value && key in actual) return@given
			if (key !in actual) {
				expected("to contain key:${show(key)} but map keys were:${show(actual.keys)}")
			} else {
				expected("to contain entry:${show(key)}=${show(value)} but was:${show(key)}=${show(actualValue)}")
			}
		}
	}

/**
 * Extension function to assert that a Map does not contain a specific value.
 */
fun <K, V> Assert<Map<K, V>>.doesNotContainValue(value: V): Assert<Map<K, V>> =
	apply {
		given { actual ->
			if (!actual.containsValue(value)) return@given
			expected("to not contain value:${show(value)} but map contained it")
		}
	}

/**
 * Extension function to assert that all elements in a collection match a predicate.
 */
fun <T> Assert<Collection<T>>.allMatch(predicate: (T) -> Boolean): Assert<Collection<T>> =
	apply {
		given { actual ->
			val failures = actual.filterNot(predicate)
			if (failures.isEmpty()) return@given
			expected("all elements to match predicate but ${failures.size} did not:${show(failures)}")
		}
	}

/**
 * Extension function to assert that a Set contains at least one null element.
 */
fun <T> Assert<Set<T?>>.containsNull(): Assert<Set<T?>> =
	apply {
		given { actual ->
			if (actual.any { it == null }) return@given
			expected("to contain null but was:${show(actual)}")
		}
	}
