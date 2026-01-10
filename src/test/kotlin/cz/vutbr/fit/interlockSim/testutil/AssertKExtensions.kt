/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * AssertK Extensions
 *
 * Custom extension functions for AssertK to support property references
 * and maintain compatibility during AssertJ to AssertK migration.
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.Assert
import assertk.assertions.support.expected
import assertk.assertions.support.show
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
 * Extension function to add a description/message to an assertion.
 *
 * This provides a fluent API similar to AssertJ's .as() method, allowing
 * descriptive messages to be added to assertions for better test failure messages.
 *
 * Usage: assertThat(value).withMessage("description").isEqualTo(expected)
 *
 * Note: This is a workaround since AssertK doesn't have a built-in chaining method
 * for descriptions. The proper AssertK way is to use the name parameter directly:
 * assertThat(value, name = "description").isEqualTo(expected)
 */
fun <T> Assert<T>.withMessage(message: String): Assert<T> {
	// Note: AssertK doesn't support post-hoc message addition like AssertJ
	// The message parameter is effectively ignored here
	// For proper message support, use: assertThat(value, name = "message").assertion()
	return this
}

/**
 * Extension function to assert that a collection has size greater than or equal to expected.
 *
 * Usage: assertThat(collection).hasSizeGreaterThanOrEqualTo(1)
 */
fun <T> Assert<Collection<T>>.hasSizeGreaterThanOrEqualTo(expected: Int) =
	given { actual ->
		if (actual.size >= expected) return@given
		expected("to have size at least:${show(expected)} but was:${show(actual.size)}")
	}

/**
 * Extension function to assert that a collection contains any of the given elements.
 *
 * Usage: assertThat(collection).containsAnyOf(element1, element2)
 */
fun <T> Assert<Collection<T>>.containsAnyOf(vararg elements: T) =
	given { actual ->
		if (elements.any { it in actual }) return@given
		expected("to contain any of:${show(elements.toList())} but was:${show(actual)}")
	}

/**
 * Extension function to assert that two objects are the same instance.
 *
 * Usage: assertThat(obj1).isSameAs(obj2)
 */
fun <T> Assert<T>.isSameAs(expected: T): Assert<T> =
	apply {
		given { actual ->
			if (actual === expected) return@given
			expected("to be the same instance as:${show(expected)} but was:${show(actual)}")
		}
	}

/**
 * Extension function to assert that two objects are not the same instance.
 *
 * Usage: assertThat(obj1).isNotSameAs(obj2)
 */
fun <T> Assert<T>.isNotSameAs(expected: T): Assert<T> =
	apply {
		given { actual ->
			if (actual !== expected) return@given
			expected("to not be the same instance as:${show(expected)}")
		}
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
 * Extension function for backwards compatibility with AssertJ's assertThatThrownBy.
 *
 * This function captures an exception thrown by a block and returns an Assert<Throwable>
 * so that AssertK's built-in isInstanceOf can be used directly.
 *
 * Usage: assertThatThrownBy { /* code that throws */ }.isInstanceOf(Exception::class)
 */
fun assertThatThrownBy(block: () -> Unit): Assert<Throwable> {
	val result = runCatching { block() }
	val exception = result.exceptionOrNull()
	if (exception == null) {
		throw AssertionError("Expected block to throw an exception, but it completed successfully")
	}
	return assertk.assertThat(exception)
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
 * Usage: assertThat { /* code */ }.isFailure().hasMessageContaining("expected text")
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
 * This overload handles the type chain when isInstanceOf() is used after isFailure(),
 * which narrows the type to Throwable or a specific exception class.
 *
 * Usage: assertThat { /* code */ }.isFailure().isInstanceOf(Exception::class).hasMessageContaining("text")
 */
@JvmName("hasMessageContainingThrowable")
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
 *
 * This provides AssertJ-compatible `containsEntry()` functionality for AssertK.
 * The assertion checks that the map contains the exact key-value pair.
 *
 * Usage: assertThat(map).containsEntry(key, value)
 *
 * @param key The expected key
 * @param value The expected value associated with the key
 * @return The same Assert instance for method chaining
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
 *
 * This checks that the value is not present in any of the map's values.
 *
 * Usage: assertThat(map).doesNotContainValue(value)
 *
 * @param value The value that should not be in the map
 * @return The same Assert instance for method chaining
 */
fun <K, V> Assert<Map<K, V>>.doesNotContainValue(value: V): Assert<Map<K, V>> =
	apply {
		given { actual ->
			if (!actual.containsValue(value)) return@given
			expected("to not contain value:${show(value)} but map contained it")
		}
	}

/**
 * Extension function for Set element containment check with explicit typing.
 *
 * Provides explicit type resolution for Set types in assertion chains.
 *
 * Usage: assertThat(set).containsElement(element)
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
 *
 * Kotlin's type system has ambiguity between AssertK's assertion method and
 * stdlib's Collection.contains(). This overload provides explicit type resolution
 * for assertion chaining contexts.
 *
 * Usage: assertThat(collection).containsElement(element)
 *
 * Note: Consider using AssertK's native .contains() directly when not chaining,
 * or using this method explicitly when the compiler cannot resolve the ambiguity.
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
 *
 * Similar to containsExactlyInAnyOrder, provides explicit type resolution.
 * Note: For sets, this is essentially the same as containsExactlyInAnyOrder
 * since sets are unordered, but we preserve the method name for API compatibility.
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
 *
 * Provides explicit type resolution for Collection types.
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
 * Extension function to assert that all elements in a collection match a predicate.
 *
 * Provides AssertJ-compatible allMatch functionality for AssertK.
 *
 * Usage: assertThat(collection).allMatch { predicate }
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
 * Extension function to assert that a collection is not empty.
 *
 * Provides explicit type resolution for collections where isEmpty() exists
 * but isNotEmpty() is not auto-generated.
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
 *
 * AssertK's built-in hasSize() returns Unit, which breaks method chaining.
 * This extension provides the same functionality but returns the Assert instance
 * to enable fluent assertion chains.
 *
 * Usage: assertThat(collection).hasSize(3).containsExactlyInAnyOrder(a, b, c)
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
 *
 * AssertK's built-in hasSize() returns Unit, which breaks method chaining.
 * This extension provides the same functionality but returns the Assert instance.
 *
 * Usage: assertThat(map).hasSize(3).containsEntry(key, value)
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
 *
 * AssertK's built-in hasSize() returns Unit, which breaks method chaining.
 * This extension provides the same functionality but returns the Assert instance.
 *
 * Usage: assertThat(set).hasSize(3).containsElement(element)
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
 * Extension function to assert that a Set contains at least one null element.
 *
 * Usage: assertThat(set).containsNull()
 */
@JvmName("containsNullSet")
fun <T> Assert<Set<T?>>.containsNull(): Assert<Set<T?>> =
	apply {
		given { actual ->
			if (actual.any { it == null }) return@given
			expected("to contain null but was:${show(actual)}")
		}
	}

/**
 * Extension function to assert that a collection contains at least one null element.
 *
 * Usage: assertThat(collection).containsNull()
 */
@JvmName("containsNullCollection")
fun <T> Assert<Collection<T?>>.containsNull(): Assert<Collection<T?>> =
	apply {
		given { actual ->
			if (actual.any { it == null }) return@given
			expected("to contain null but was:${show(actual)}")
		}
	}
