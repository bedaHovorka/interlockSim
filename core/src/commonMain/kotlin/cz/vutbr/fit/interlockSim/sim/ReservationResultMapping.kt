/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.lang.vocab.Aspect
import cz.vutbr.fit.interlockSim.lang.vocab.BlockId
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.lang.vocab.TrainRoute
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The **one** mapping across the kernel → facade boundary (Issue #968, owner ruling D7): turns a
 * [PathReservationService.ReservationResult] into the [InterlockingFacade.RouteResponse] that
 * [DefaultInterlockingFacade.requestRouteByEndpoints] returns.
 *
 * The `when` is exhaustive with no `else`, so a new `ReservationResult` subtype is a compile error
 * here rather than a silent collapse. Both callers share it: the facade, and the legacy/no-facade
 * branch of [cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort.requestRoute], which
 * composes this function with the port's own `DenialCause` mapping — so the two branches can no
 * longer classify the same kernel outcome differently (Issue #834 task alpha-7a).
 *
 * Every payload is forwarded from the kernel result unchanged; nothing is newly computed.
 *
 * @param trainName        The requesting train, for the log line only.
 * @param fromEndpointName The requested origin, as the caller named it.
 * @param toEndpointName   The requested target, as the caller named it.
 * @since Issue #968
 */
internal fun PathReservationService.ReservationResult.toRouteResponse(
	trainName: String,
	fromEndpointName: String,
	toEndpointName: String
): InterlockingFacade.RouteResponse =
	when (this) {
		is PathReservationService.ReservationResult.Success -> {
			// Every physically reserved block must be represented here, even if unnamed —
			// silently dropping unnamed blocks (via mapNotNull on the name) would undercount
			// blocksCount downstream in DefaultNetworkActuatorPort.requestRoute's
			// RouteRequestResult.Reserved, which is what the dispatcher/tool caller observes.
			val blocks = reservedBlocks.mapIndexed { index, b -> BlockId(b.name ?: "unnamed-$index") }
			val route =
				TrainRoute(
					from = SignalId(fromEndpointName),
					to = SignalId(toEndpointName),
					running = emptyList(),
					blocks = blocks
				)
			logger.info {
				"Route GRANTED (by endpoints) for trainId=$trainName: " +
					"${blocks.size} blocks reserved ($fromEndpointName → $toEndpointName)"
			}
			InterlockingFacade.RouteResponse.Granted(Aspect.Volno, route)
		}
		is PathReservationService.ReservationResult.NoPathExists -> {
			logger.info {
				"Route DENIED for trainId=$trainName: no path exists $fromEndpointName → $toEndpointName"
			}
			InterlockingFacade.RouteResponse.Denied(
				"No path exists: $fromEndpointName → $toEndpointName",
				InterlockingFacade.RouteResponse.DenialCause.NoPath
			)
		}
		is PathReservationService.ReservationResult.AllPathsBlocked -> {
			logger.info {
				"Route DENIED for trainId=$trainName: all paths blocked " +
					"(attempts: $attemptedPaths, $fromEndpointName → $toEndpointName)"
			}
			InterlockingFacade.RouteResponse.Denied(
				"All paths blocked ($fromEndpointName → $toEndpointName, attempts: $attemptedPaths)",
				// The same count that is formatted into the reason above, now also carried
				// machine-readably: before Issue #834 task alpha-7a the facade branch of
				// DefaultNetworkActuatorPort reported attemptedPaths=0 for every denial,
				// contradicting RouteRequestResult.AllPathsBlocked's own contract.
				InterlockingFacade.RouteResponse.DenialCause.AllPathsBlocked(attemptedPaths)
			)
		}
		is PathReservationService.ReservationResult.Conflict -> {
			val blockName = conflictingBlock.name ?: "?"
			logger.info {
				"Route DENIED for trainId=$trainName: conflict at block $blockName (train $existingOwner)"
			}
			InterlockingFacade.RouteResponse.Denied(
				"Block $blockName occupied by train $existingOwner",
				// The cause carries the block's REAL name (null when unnamed), not the "?"
				// placeholder the human-readable reason substitutes, so a caller can identify
				// the block rather than re-parse the text.
				InterlockingFacade.RouteResponse.DenialCause.Conflict(
					blockName = conflictingBlock.name,
					existingOwner = existingOwner
				)
			)
		}
		is PathReservationService.ReservationResult.NonContiguousStart -> {
			// Issue #893 (task A-R1b): the requested origin is nowhere near this train. The
			// reason string already names the origin and the legal alternatives, so it is
			// forwarded as-is; the NonContiguousStart cause lets DefaultNetworkActuatorPort
			// map this denial to RouteRequestResult.OriginNotContiguous.
			logger.info {
				"Route DENIED for trainId=$trainName: non-contiguous origin ($fromEndpointName): $reason"
			}
			InterlockingFacade.RouteResponse.Denied(
				reason,
				InterlockingFacade.RouteResponse.DenialCause.NonContiguousStart
			)
		}
		is PathReservationService.ReservationResult.GeometricallyImpossible -> {
			// Issue #903: a permanent impossibility (rear-facing START or unconfigurable
			// switch), not ordinary contention. The GeometricallyImpossible cause lets
			// DefaultNetworkActuatorPort map this denial to its own
			// RouteRequestResult.GeometricallyImpossible, excluded from the contention bucket.
			logger.info {
				"Route DENIED for trainId=$trainName: geometrically impossible " +
					"($fromEndpointName → $toEndpointName): $reason"
			}
			InterlockingFacade.RouteResponse.Denied(
				reason,
				InterlockingFacade.RouteResponse.DenialCause.GeometricallyImpossible(reason)
			)
		}
		is PathReservationService.ReservationResult.DivergesFromHeldRoute -> {
			// Issue #1066: nothing was mutated and no block was busy, so not AllPathsBlocked.
			logger.info { "Route DENIED for trainId=$trainName: diverges from held route: $reason" }
			InterlockingFacade.RouteResponse.Denied(
				"Route already continues toward $heldTarget; extend from it or cancel the route first",
				InterlockingFacade.RouteResponse.DenialCause.DivergesFromHeldRoute(heldTarget, reason)
			)
		}
	}
