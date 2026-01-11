/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.exceptions

/**
 * Severity level for exceptions in the interlocking simulator
 *
 * Defines how critical an exception is and how the application should respond:
 * - FATAL: Must end operation immediately (simulation or editor)
 * - ERROR: Can be recovered by user decision, but should be handled ASAP
 * - WARN: Can be fixed by user decision later, non-critical
 */
enum class Severity {
	/**
	 * Fatal error - must terminate operation immediately
	 */
	FATAL,

	/**
	 * Error - can be recovered by user decision but requires immediate attention
	 */
	ERROR,

	/**
	 * Warning - can be fixed by user decision later
	 */
	WARN
}
