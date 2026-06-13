package cz.vutbr.fit.interlockSim.util

actual fun currentTimeMillisKMP(): Long = System.currentTimeMillis()

actual fun getNextScheduledEventTime(sim: cz.hovorka.kdisco.Simulation): Double {
	return try {
		val getContextMethod = sim::class.java.getMethod("getContext\$kdisco_core")
		val context = getContextMethod.invoke(sim)
		val getEventQueueMethod = context::class.java.getMethod("getEventQueue")
		val eventQueue = getEventQueueMethod.invoke(context)
		val peekMethod = eventQueue::class.java.getMethod("peek")
		val scheduledEvent = peekMethod.invoke(eventQueue) ?: return Double.MAX_VALUE
		val getTimeMethod = scheduledEvent::class.java.getMethod("getTime")
		getTimeMethod.invoke(scheduledEvent) as Double
	} catch (e: Exception) {
		Double.MAX_VALUE
	}
}

