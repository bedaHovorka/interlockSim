/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.BaseContext
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener

/**
 * Shared property-change test scaffolding for the context lifecycle workflow tests
 * (Issue #1035 review round), used by
 * [cz.vutbr.fit.interlockSim.integration.EditToSimulationWorkflowTest] and
 * [cz.vutbr.fit.interlockSim.integration.ContextLifecycleIntegrationTest].
 *
 * Test PropertyChangeListener implementation that captures all events.
 */
class TestPropertyChangeListener : ContextPropertyChangeListener {
	val events: MutableList<ContextChangeEvent> = mutableListOf()

	override fun propertyChange(event: ContextChangeEvent) {
		events.add(event)
	}
}

/**
 * Set [context]'s max speed to [newMaxSpeed] and verify the value took effect
 * and exactly one "currentMaxSpeed" event fired with the new value.
 */
fun assertMaxSpeedPropertyEventFires(
	context: BaseContext<*>,
	newMaxSpeed: Double
) {
	val propertyListener = TestPropertyChangeListener()
	context.addPropertyChangeListener(propertyListener)

	// Verify property setter fires event
	context.currentMaxSpeed = newMaxSpeed
	assertThat(context.currentMaxSpeed).isEqualTo(newMaxSpeed)

	// Verify event was fired
	assertThat(propertyListener.events).hasSize(1)
	val event = propertyListener.events[0]
	assertThat(event.propertyName).isEqualTo("currentMaxSpeed")
	assertThat(event.newValue).isEqualTo(newMaxSpeed)
}

/** Assert that the three network properties of [context] equal the expected values. */
fun assertNetworkProperties(
	context: BaseContext<*>,
	expectedMaxSpeed: Double,
	expectedTrackLength: Double,
	expectedName: String
) {
	assertThat(context.currentMaxSpeed).isEqualTo(expectedMaxSpeed)
	assertThat(context.currentTrackLength).isEqualTo(expectedTrackLength)
	assertThat(context.currentNameString).isEqualTo(expectedName)
}

/**
 * Assert that [context] is frozen ([expected] `true`, the default) or still mutable
 * ([expected] `false`). Editing contexts are mutable; simulation contexts are frozen.
 */
fun assertFrozen(
	context: BaseContext<*>,
	expected: Boolean = true
) {
	assertThat(context.isFrozen, name = "isFrozen").isEqualTo(expected)
}
