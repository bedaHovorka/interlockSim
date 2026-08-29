/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.exceptions

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/*
 * Utility functions for validation and requirement checking.
 * These functions replace assert() calls and if-throw boilerplate.
 * They throw appropriate exceptions with meaningful messages.
 */

/**
 * Requires that the given value is true for simulation logic.
 * Throws SimulationException if the value is false.
 *
 * @param value The boolean value to check
 * @param lazyMessage A function producing the error message (only evaluated if value is false)
 * @throws SimulationException if value is false
 */
@OptIn(ExperimentalContracts::class)
inline fun requireSimulation(
	value: Boolean,
	lazyMessage: () -> String = { "Simulation requirement failed" }
) {
	contract {
		returns() implies value
	}
	if (!value) {
		throw SimulationException(lazyMessage())
	}
}

/**
 * Requires that the given value is true for simulation logic.
 * Throws SimulationException with specified severity if the value is false.
 *
 * @param value The boolean value to check
 * @param severity Severity level of the exception
 * @param lazyMessage A function producing the error message (only evaluated if value is false)
 * @throws SimulationException if value is false
 */
@OptIn(ExperimentalContracts::class)
inline fun requireSimulation(
	value: Boolean,
	severity: Severity,
	lazyMessage: () -> String = { "Simulation requirement failed" }
) {
	contract {
		returns() implies value
	}
	if (!value) {
		throw SimulationException(severity, lazyMessage(), null, null)
	}
}

/**
 * Requires that the given value is not null for simulation logic.
 * Throws SimulationException if the value is null.
 *
 * @param value The value to check
 * @param lazyMessage A function producing the error message (only evaluated if value is null)
 * @return The non-null value
 * @throws SimulationException if value is null
 */
@OptIn(ExperimentalContracts::class)
inline fun <T : Any> requireSimulationNotNull(
	value: T?,
	lazyMessage: () -> String = { "Required value was null" }
): T {
	contract {
		returns() implies (value != null)
	}
	if (value == null) {
		throw SimulationException(lazyMessage())
	}
	return value
}

/**
 * Requires that the given state is valid for simulation logic.
 * Throws SimulationException if the value is false.
 *
 * @param value The boolean value to check
 * @param lazyMessage A function producing the error message (only evaluated if value is false)
 * @throws SimulationException if value is false
 */
@OptIn(ExperimentalContracts::class)
inline fun requireSimulationState(
	value: Boolean,
	lazyMessage: () -> String = { "Invalid simulation state" }
) {
	contract {
		returns() implies value
	}
	if (!value) {
		throw SimulationException(lazyMessage())
	}
}

/**
 * Requires that the given value is true for editor operations.
 * Throws EditorException if the value is false.
 *
 * @param value The boolean value to check
 * @param lazyMessage A function producing the error message (only evaluated if value is false)
 * @throws EditorException if value is false
 */
@OptIn(ExperimentalContracts::class)
inline fun requireEditor(
	value: Boolean,
	lazyMessage: () -> String = { "Editor requirement failed" }
) {
	contract {
		returns() implies value
	}
	if (!value) {
		throw EditorException(lazyMessage())
	}
}

/**
 * Requires that the given value is true for editor operations.
 * Throws EditorException with specified severity if the value is false.
 *
 * @param value The boolean value to check
 * @param severity Severity level of the exception
 * @param lazyMessage A function producing the error message (only evaluated if value is false)
 * @throws EditorException if value is false
 */
@OptIn(ExperimentalContracts::class)
inline fun requireEditor(
	value: Boolean,
	severity: Severity,
	lazyMessage: () -> String = { "Editor requirement failed" }
) {
	contract {
		returns() implies value
	}
	if (!value) {
		throw EditorException(severity, lazyMessage())
	}
}

/**
 * Requires that the given value is not null for editor operations.
 * Throws EditorException if the value is null.
 *
 * @param value The value to check
 * @param lazyMessage A function producing the error message (only evaluated if value is null)
 * @return The non-null value
 * @throws EditorException if value is null
 */
@OptIn(ExperimentalContracts::class)
inline fun <T : Any> requireEditorNotNull(
	value: T?,
	lazyMessage: () -> String = { "Required value was null" }
): T {
	contract {
		returns() implies (value != null)
	}
	if (value == null) {
		throw EditorException(lazyMessage())
	}
	return value
}

/**
 * Requires that the given argument is valid.
 * Throws IllegalArgumentException if the value is false.
 *
 * @param value The boolean value to check
 * @param lazyMessage A function producing the error message (only evaluated if value is false)
 * @throws IllegalArgumentException if value is false
 */
@OptIn(ExperimentalContracts::class)
inline fun requireValidArgument(
	value: Boolean,
	lazyMessage: () -> String = { "Invalid argument" }
) {
	contract {
		returns() implies value
	}
	require(value) { lazyMessage() }
}

/**
 * Requires that the given state is valid.
 * Throws IllegalStateException if the value is false.
 *
 * @param value The boolean value to check
 * @param lazyMessage A function producing the error message (only evaluated if value is false)
 * @throws IllegalStateException if value is false
 */
@OptIn(ExperimentalContracts::class)
inline fun requireValidState(
	value: Boolean,
	lazyMessage: () -> String = { "Invalid state" }
) {
	contract {
		returns() implies value
	}
	check(value) { lazyMessage() }
}
