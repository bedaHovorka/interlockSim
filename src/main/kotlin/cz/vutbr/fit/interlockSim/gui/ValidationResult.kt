/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

// Backward-compatibility re-exports: types moved to cz.vutbr.fit.interlockSim.util
// These type aliases allow existing gui/ code and tests to continue using the
// simple names without updating imports.

typealias ValidationResult = cz.vutbr.fit.interlockSim.util.ValidationResult
typealias ValidationError = cz.vutbr.fit.interlockSim.util.ValidationError
typealias ValidationWarning = cz.vutbr.fit.interlockSim.util.ValidationWarning
typealias ErrorCategory = cz.vutbr.fit.interlockSim.util.ErrorCategory
typealias Severity = cz.vutbr.fit.interlockSim.util.Severity
