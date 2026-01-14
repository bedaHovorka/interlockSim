/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

/**
 * Backward compatibility alias for {@link DefaultSimulationContext}.
 *
 * **This class is deprecated.** Use {@link DefaultSimulationContext} directly instead.
 *
 * ## Migration Guide
 *
 * Replace:
 * ```kotlin
 * abstract class MyContext(...) : DefaultContext(...) { }
 * ```
 *
 * With:
 * ```kotlin
 * abstract class MyContext(...) : DefaultSimulationContext(...) { }
 * ```
 *
 * ## Rationale
 *
 * The original DefaultContext combined both editing and simulation concerns.
 * Phase 3 of the Context refactoring split this into:
 * - {@link DefaultEditingContext} - Editing operations only
 * - {@link DefaultSimulationContext} - Extends editing with simulation
 *
 * This deprecated alias maintains backward compatibility during the transition period.
 *
 * @see DefaultSimulationContext
 * @see DefaultEditingContext
 * @see EditingContext
 * @see SimulationContext
 * @deprecated Use {@link DefaultSimulationContext} instead
 */
@Deprecated(
	message = "Use DefaultSimulationContext instead",
	replaceWith = ReplaceWith("DefaultSimulationContext", "cz.vutbr.fit.interlockSim.context.DefaultSimulationContext"),
	level = DeprecationLevel.WARNING
)
abstract class DefaultContext(
	cols: Int,
	rows: Int,
	/**
	 * Factory for creating simulation processes.
	 * Decouples context from concrete simulation class implementations.
	 */
	processFactory: SimulationProcessFactory
) : DefaultSimulationContext(cols, rows, processFactory) {
	@Deprecated(
		message = "Use primary constructor DefaultSimulationContext(cols, rows, processFactory)",
		replaceWith = ReplaceWith(
			"DefaultSimulationContext(cols, rows, processFactory)",
			"cz.vutbr.fit.interlockSim.context.DefaultSimulationContext"
		),
		level = DeprecationLevel.WARNING
	)
	protected constructor(
		processFactory: SimulationProcessFactory
	) : this(0, 0, processFactory) {
		// This constructor is kept for backward compatibility but should not be used
		throw UnsupportedOperationException(
			"This constructor is deprecated. Use DefaultSimulationContext(cols, rows, processFactory) instead."
		)
	}
}
