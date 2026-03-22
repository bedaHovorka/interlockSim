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
