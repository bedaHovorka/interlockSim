/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.SeparatorKind
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.chosenTargetName

/**
 * Issue #1387: the per-tick differential observer of the platform steer, shared by the simulation
 * tests that wire it around the dispatcher step (initially `PrahaEntryPlatformExitTest`'s Praha
 * checker, then shared with `CervenyUjezdSteeredPickFlatTest`). On every observation it re-derives
 * the pre-#1387 pick (the reachable set emptied) and asserts the ruled invariants:
 * - the reachable set is non-empty only where the steer applies: not while the own exit is listed
 *   itself (the #1278/#1299 rules decide), not while an extension is awaited (the #1244 guard), and
 *   not without a destination;
 * - every reachable name is one of the observation's signal candidates;
 * - with every reachable signal busy, the pick equals the fixed order (the owner's fallback clause);
 * - when a reachable signal is available, the pick is one of them (step 3), and otherwise the
 *   fallback applied.
 *
 * [checkObservation] throws its assertion failure through the simulation, so wiring it before the
 * dispatcher step in the `ControlStepListener` turns the run itself into the differential tripwire.
 *
 * JVM-only (`jvmMain`) like the other JVM helpers of this module: the iterable and boolean asserts
 * the checks need ([assertk.assertions.isIn] over the candidate names) are JVM artifact
 * specializations. Without an applicable steer the checks are true by definition, so
 * [checkObservation] skips the pick re-derivations for those observations and only counts them.
 */
class SteeredPickObserver {
	/** How many observations [checkObservation] has checked. */
	var inputsChecked: Int = 0
		private set

	/** How many observations saw the reachable set change the pick. */
	var steeredPicks: Int = 0
		private set

	/** Checks the Issue #1387 invariants of one observation. */
	fun checkObservation(input: BlockInputObservation) {
		inputsChecked++
		val reachable = input.destinationReachableVia
		val ownExitListed =
			input.candidateTargets.any { it.kind == SeparatorKind.IN_OUT && it.name == input.destinationName }
		if (ownExitListed || input.awaitingRouteExtension || input.destinationName == null) {
			// TSE fixes 1 and 2: the #1299 wait and the #1244 guard see the old observation.
			assertThat(reachable.isEmpty(), name = "reachable set of ${input.blockId}>${input.towardSemaphoreName} isEmpty")
				.isTrue()
			return
		}
		// Without the steer the invariants below are true by definition: with an empty reachable set
		// pick and old pick are the same call, so only the guard's asserts are evidence.
		if (reachable.isEmpty()) return
		val oldPick = input.copy(destinationReachableVia = emptySet()).chosenTargetName()
		// The owner's fallback clause: with every reachable signal busy the pick is the old one.
		// Compared within the busy variant itself (both calls see its candidate list), so the claim
		// is "no reachable candidate decides the pick", not "the pick equals the original".
		val allReachableBusy =
			input.copy(
				candidateTargets = input.candidateTargets.map { if (it.name in reachable) it.copy(available = false) else it }
			)
		assertThat(allReachableBusy.chosenTargetName(), name = "pick with every reachable signal busy")
			.isEqualTo(allReachableBusy.copy(destinationReachableVia = emptySet()).chosenTargetName())
		val signalNames = input.candidateTargets.filter { it.kind == SeparatorKind.SEMAPHORE }.map { it.name }
			.toTypedArray()
		reachable.forEach { assertThat(it, name = "reachable candidate").isIn(*signalNames) }
		val pick = input.chosenTargetName()
		if (input.candidateTargets.any { it.available && it.name in reachable }) {
			assertThat(pick in reachable, name = "pick $pick is reachable for ${input.destinationName}").isTrue()
			if (pick != oldPick) steeredPicks++
		} else {
			assertThat(pick, name = "fallback pick").isEqualTo(oldPick)
		}
	}

	/**
	 * The control-step listener of a #1387 differential run: checks every block input of [loop], then
	 * runs [dispatcherStep]. The checks must wrap the dispatcher step so they see exactly the
	 * current tick's pre-decision observations, so this helper is the one place the ordering lives.
	 */
	fun controlStepListener(
		loop: ShuntingLoop,
		dispatcherStep: ControlStepListener
	): ControlStepListener =
		ControlStepListener {
			for (blockInput in loop.getInnerBlockInputs()) checkObservation(blockInput)
			for (blockInput in loop.getOuterBlockInputs()) checkObservation(blockInput)
			dispatcherStep.onControlStep()
		}
}
