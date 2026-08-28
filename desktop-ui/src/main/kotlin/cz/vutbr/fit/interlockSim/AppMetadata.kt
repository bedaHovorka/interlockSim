/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

/*
 * Application metadata constants.
 *
 * These constants define the application identity and version information
 * used in window titles, about dialogs, and logging.
 */

/** Application name */
const val PROGRAM_NAME = "InterlockSim"

/** Application version */
const val PROGRAM_VERSION = "0.2"

/** Full application title (name + version) */
const val PROGRAM_FULL_NAME = "$PROGRAM_NAME $PROGRAM_VERSION"

/**
 * Window title used while a run is driven by the LLM dispatcher (Issue #839).
 *
 * The plain [PROGRAM_FULL_NAME] stays in place for every other run, so the title bar
 * alone tells the operator whether the decisions come from the LLM arm.
 */
const val PROGRAM_LLM_FULL_NAME = "$PROGRAM_NAME + LLM dispatcher $PROGRAM_VERSION"
