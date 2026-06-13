package cz.vutbr.fit.interlockSim.util

actual fun currentTimeMillisKMP(): Long = System.currentTimeMillis()

actual fun getNextScheduledEventTime(sim: cz.hovorka.kdisco.Simulation): Double {
	return try {
		val getContextMethod = sim::class.java.getMethod("getContext\$kdisco_core")
		getContextMethod.isAccessible = true
		val context = getContextMethod.invoke(sim)

		val getPendingActivationsMethod = context::class.java.getMethod("getPendingActivations")
		getPendingActivationsMethod.isAccessible = true
		val pendingActivations = getPendingActivationsMethod.invoke(context) as List<*>
		if (pendingActivations.isNotEmpty()) {
			return sim.time()
		}

		val getEventQueueMethod = context::class.java.getMethod("getEventQueue")
		getEventQueueMethod.isAccessible = true
		val eventQueue = getEventQueueMethod.invoke(context)
		val peekMethod = eventQueue::class.java.getMethod("peek")
		peekMethod.isAccessible = true
		val scheduledEvent = peekMethod.invoke(eventQueue) ?: return Double.MAX_VALUE
		val getTimeMethod = scheduledEvent::class.java.getMethod("getTime")
		getTimeMethod.isAccessible = true
		getTimeMethod.invoke(scheduledEvent) as Double
	} catch (e: Exception) {
		Double.MAX_VALUE
	}
}

