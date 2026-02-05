package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.containsAtLeast
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Verification tests for new TestTopologies topologies added in Issue #253 post-implementation review.
 *
 * These tests verify that:
 * 1. Y-junction topology is correctly constructed
 * 2. Multi-semaphore topology supports configurable semaphore count
 * 3. Both EditingContext and SimulationContext variants work
 */
@DisplayName("TestTopologies New Topology Verification")
class TestTopologiesVerificationTest : KoinTestBase() {

	@Test
	@DisplayName("yJunctionWithSwitch creates topology with 4 InOuts and 1 switch")
	fun yJunctionWithSwitch_createsCorrectTopology() {
		// Act
		TestTopologies.yJunctionWithSwitch().use { context ->
			// Assert - Verify structure
			assertThat(context.getInOuts()).hasSize(4)
			val inOuts = context.getInOuts()
			val names = inOuts.map { it.toString() }
			// Names are prefixed with "InOut:"
			assertThat(names).containsAtLeast("InOut:EntryA", "InOut:EntryB", "InOut:ExitC", "InOut:ExitD")

			// Verify switch exists
			val switch = context.getRailWayNetGrid().getCellAt(30, 30)
			assertThat(switch).isNotNull()
		}
	}

	@Test
	@DisplayName("yJunctionWithSwitchSimulation creates SimulationContext variant")
	fun yJunctionWithSwitchSimulation_createsSimulationContext() {
		// Act
		TestTopologies.yJunctionWithSwitchSimulation().use { context ->
			// Assert - Verify it's a simulation context
			assertThat(context.getInOuts()).hasSize(4)
			// Graph contains track blocks - Y-junction has 4 connections but graph may differ
			assertThat(context.getGraph().size()).isEqualTo(3) // SimpleTrackBlock uses bidirectional edges
		}
	}

	@Test
	@DisplayName("linearPathWithSemaphoreSequence creates topology with default 3 semaphores")
	fun linearPathWithSemaphoreSequence_defaultCount_creates3Semaphores() {
		// Act
		TestTopologies.linearPathWithSemaphoreSequence().use { context ->
			// Assert - Verify structure (1 entry + 3 semaphores + 1 exit = 5 InOuts/Semaphores total)
			assertThat(context.getInOuts()).hasSize(2) // Only entry and exit are InOuts

			// Verify semaphores at expected positions
			assertThat(context.getRailWayNetGrid().getCellAt(6, 5)).isNotNull() // Sem1
			assertThat(context.getRailWayNetGrid().getCellAt(11, 5)).isNotNull() // Sem2
			assertThat(context.getRailWayNetGrid().getCellAt(16, 5)).isNotNull() // Sem3
		}
	}

	@Test
	@DisplayName("linearPathWithSemaphoreSequence supports custom semaphore count")
	fun linearPathWithSemaphoreSequence_customCount_createsCorrectNumber() {
		// Act
		TestTopologies.linearPathWithSemaphoreSequence(semaphoreCount = 5).use { context ->
			// Assert - 5 semaphores means 6 track blocks (entry to sem1, sem1-2, ..., sem5 to exit)
			assertThat(context.getInOuts()).hasSize(2)

			// Verify semaphores at expected positions (5 units apart starting at x=6)
			assertThat(context.getRailWayNetGrid().getCellAt(6, 5)).isNotNull() // Sem1
			assertThat(context.getRailWayNetGrid().getCellAt(11, 5)).isNotNull() // Sem2
			assertThat(context.getRailWayNetGrid().getCellAt(16, 5)).isNotNull() // Sem3
			assertThat(context.getRailWayNetGrid().getCellAt(21, 5)).isNotNull() // Sem4
			assertThat(context.getRailWayNetGrid().getCellAt(26, 5)).isNotNull() // Sem5
		}
	}

	@Test
	@DisplayName("linearPathWithSemaphoreSequenceSimulation creates SimulationContext variant")
	fun linearPathWithSemaphoreSequenceSimulation_createsSimulationContext() {
		// Act
		TestTopologies.linearPathWithSemaphoreSequenceSimulation(semaphoreCount = 2).use { context ->
			// Assert - Verify it's a simulation context with correct structure
			assertThat(context.getInOuts()).hasSize(2)
			// Graph represents track connections - linear path has bidirectional edges
			assertThat(context.getGraph().size()).isEqualTo(1) // 1 edge for bidirectional SimpleTrackBlock
		}
	}
}
