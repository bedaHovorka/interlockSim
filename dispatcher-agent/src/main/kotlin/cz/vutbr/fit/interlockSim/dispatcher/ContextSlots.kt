/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import org.koin.core.scope.Scope
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Per-context slots for the instances a wiring layer publishes into one simulation context — the
 * measuring planner, the pacing controller, the snapshot signal and the like (Issue #1348).
 *
 * ## Why this exists
 *
 * Those instances used to be published with Koin's `Scope.declare`. In Koin 3.5 that call
 * registers ONE definition per scope *qualifier*, which every `DefaultSimulationContext` scope
 * shares, and the definition's builder returns the instance of the first scope that declared it. A
 * later context that never declared the type therefore resolved the first context's instance —
 * even after that context was closed (the leak #1317 fixed for the agent driver loop alone).
 *
 * This class is bound `scoped` on the `DefaultSimulationContext` scope in `dispatcherAgentModule`,
 * so Koin builds a fresh, empty one for every context. Wiring layers call [Scope.publish]; readers
 * call [Scope.publishedOrNull]. Never `Scope.declare` a type that has no `scoped` definition.
 *
 * Thread-safe: wiring publishes on the caller's thread, readers run on the EDT or the run's thread.
 */
class ContextSlots {
	private val slots = ConcurrentHashMap<KClass<*>, Any>()

	/**
	 * Publishes [instance] under [type] in this context.
	 *
	 * @throws IllegalStateException if this context already published an instance of [type].
	 */
	fun <T : Any> put(
		type: KClass<T>,
		instance: T
	) {
		check(slots.putIfAbsent(type, instance) == null) {
			"A ${type.simpleName} is already published in this simulation context"
		}
	}

	/** The instance published under [type] in this context, or `null` when none was. */
	fun <T : Any> get(type: KClass<T>): T? = slots[type]?.let { type.javaObjectType.cast(it) }
}

/**
 * Publishes [instance] into this simulation context scope under the type [T] — pass [T] explicitly
 * to publish under a supertype. Use this, never `Scope.declare`: see [ContextSlots] for why.
 */
inline fun <reified T : Any> Scope.publish(instance: T) = get<ContextSlots>().put(T::class, instance)

/**
 * The instance published into this simulation context scope under the type [T], or `null` when the
 * context published none, the slots are not bound, or the scope is already closed.
 */
inline fun <reified T : Any> Scope.publishedOrNull(): T? = getOrNull<ContextSlots>()?.get(T::class)
