package cz.vutbr.fit.interlockSim.util

/**
 * Starts a named daemon thread that runs [action] as a blocking coroutine.
 *
 * KMP expect/actual: the JVM implementation launches a daemon [Thread] wrapping
 * `runBlocking { action() }`, so the JVM can exit cleanly when the simulation
 * finishes. Used by the SP0.11 dispatcher-agent driver loop
 * ([cz.vutbr.fit.interlockSim.sim.ShuntingLoop.agentDriverAction]), which is a
 * JVM-only feature — the native target has no agent driver and its actual fails
 * fast if ever invoked.
 *
 * @param name Thread name (for diagnostics, e.g. "dispatcher-agent-driver")
 * @param action Suspend action to run for the thread's lifetime
 */
expect fun platformStartDaemonThread(
	name: String,
	action: suspend () -> Unit
)
