/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading

/**
 * The cycle message's "standing at signal" predicate over one train's captured perception: the
 * name of the signal ahead when the train is stopped, `null` when it is moving or has no signal
 * ahead. One definition for the three consumers that had to agree — `KoogDispatchAgentImpl`'s
 * "standing at signal" prompt clause, `RequestRouteTool`'s origin guard, and
 * `NextSectionOfferCounter`'s per-train standing count — so the prompt and the tool guard can
 * never disagree about whether a train is stopped in front of a named signal.
 *
 * A train with **no** perception is not standing either; deciding that is the caller's job (the
 * counter reads it off `observation.snapshot`'s nullability, the tool and the prompt read it only
 * when a perception exists), so [standingAtSignalName] sees one perception, not a train.
 */
internal fun TrainPerceptionReading.standingAtSignalName(): String? =
	takeIf { velocity == 0.0 }?.signalAheadName?.takeIf { it.isNotBlank() }
