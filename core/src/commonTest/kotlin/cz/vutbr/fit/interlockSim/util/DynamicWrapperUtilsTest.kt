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
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.buildMinimalSimulation
import kotlin.test.Test

class DynamicWrapperUtilsTest : CommonKoinTestBase() {
	// --- unwrapToStatic ---

	@Test
	fun nullInput() {
		val result = DynamicWrapperUtils.unwrapToStatic(null)
		assertThat(result).isNull()
	}

	@Test
	fun unwrapDynamicInOut() {
		buildMinimalSimulation().use { context ->
			// Get a dynamic InOut from the context
			val dynamicInOut = context.getInOuts().first()
			assertThat(dynamicInOut).isNotNull()

			// Unwrap to static reference
			val result = DynamicWrapperUtils.unwrapToStatic(dynamicInOut)

			// Verify it's the same static object
			assertThat(result).isSameInstanceAs(dynamicInOut.staticRef)
		}
	}

	@Test
	fun unwrapDynamicRailSemaphore() {
		buildMinimalSimulation().use { context ->
			// Get a dynamic InOut and access its semaphore
			val dynamicInOut = context.getInOuts().first()
			val dynamicSemaphore = dynamicInOut.inSemaphore
			assertThat(dynamicSemaphore).isNotNull()

			// Unwrap to static reference
			val result = DynamicWrapperUtils.unwrapToStatic(dynamicSemaphore)

			// Verify it's the same static object
			assertThat(result).isSameInstanceAs(dynamicSemaphore.staticRef)
		}
	}

	@Test
	fun passthroughStaticObject() {
		buildMinimalSimulation().use { context ->
			// Get a static InOut reference
			val dynamicInOut = context.getInOuts().first()
			val staticInOut = dynamicInOut.staticRef
			assertThat(staticInOut).isNotNull()

			// Unwrap should return the same object (passthrough)
			val result = DynamicWrapperUtils.unwrapToStatic(staticInOut)

			// Verify it's the exact same instance
			assertThat(result).isSameInstanceAs(staticInOut)
		}
	}

	@Test
	fun idempotentDynamicInOut() {
		buildMinimalSimulation().use { context ->
			val dynamicInOut = context.getInOuts().first()

			val result1 = DynamicWrapperUtils.unwrapToStatic(dynamicInOut)
			val result2 = DynamicWrapperUtils.unwrapToStatic(result1)

			assertThat(result1).isSameInstanceAs(result2)
		}
	}

	@Test
	fun idempotentDynamicRailSemaphore() {
		buildMinimalSimulation().use { context ->
			val dynamicSemaphore = context.getInOuts().first().inSemaphore

			val result1 = DynamicWrapperUtils.unwrapToStatic(dynamicSemaphore)
			val result2 = DynamicWrapperUtils.unwrapToStatic(result1)

			assertThat(result1).isSameInstanceAs(result2)
		}
	}

	@Test
	fun idempotentStaticObject() {
		buildMinimalSimulation().use { context ->
			val staticInOut = context.getInOuts().first().staticRef

			val result1 = DynamicWrapperUtils.unwrapToStatic(staticInOut)
			val result2 = DynamicWrapperUtils.unwrapToStatic(result1)
			val result3 = DynamicWrapperUtils.unwrapToStatic(result2)

			assertThat(result1).isSameInstanceAs(staticInOut)
			assertThat(result2).isSameInstanceAs(staticInOut)
			assertThat(result3).isSameInstanceAs(staticInOut)
		}
	}
}
