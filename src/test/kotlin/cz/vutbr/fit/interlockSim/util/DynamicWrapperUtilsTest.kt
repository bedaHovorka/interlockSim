/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.buildMinimal
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("DynamicWrapperUtils")
class DynamicWrapperUtilsTest : KoinTestBase() {
	@Nested
	@DisplayName("unwrapToStatic")
	inner class UnwrapToStatic {
		@Test
		@DisplayName("returns null when input is null")
		fun nullInput() {
			val result = DynamicWrapperUtils.unwrapToStatic(null)
			assertThat(result).isNull()
		}

		@Test
		@DisplayName("unwraps DynamicInOut to static InOut")
		fun unwrapDynamicInOut() {
			val context: SimulationContext = buildMinimal()

			// Get a dynamic InOut from the context
			val dynamicInOut = context.getInOuts().first()
			assertThat(dynamicInOut).isNotNull()

			// Unwrap to static reference
			val result = DynamicWrapperUtils.unwrapToStatic(dynamicInOut)

			// Verify it's the same static object
			assertThat(result).isSameInstanceAs(dynamicInOut.staticRef)
		}

		@Test
		@DisplayName("unwrapping is idempotent - unwrapping twice returns same result")
		fun idempotent() {
			val context: SimulationContext = buildMinimal()
			val dynamicInOut = context.getInOuts().first()

			val result1 = DynamicWrapperUtils.unwrapToStatic(dynamicInOut)
			val result2 = DynamicWrapperUtils.unwrapToStatic(result1)

			assertThat(result1).isSameInstanceAs(result2)
		}
	}
}
