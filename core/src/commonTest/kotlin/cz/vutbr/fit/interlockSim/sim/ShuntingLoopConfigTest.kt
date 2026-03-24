package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ShuntingLoop] companion object constants that are accessible
 * without a full simulation environment.
 */
class ShuntingLoopConfigTest {

	@Test
	fun enabledReportTypesContainsTrainApproved() {
		assertContains(ShuntingLoop.ENABLED_REPORT_TYPES, ReportType.TRAIN_APPROVED)
	}

	@Test
	fun enabledReportTypesContainsTrainEvents() {
		assertContains(ShuntingLoop.ENABLED_REPORT_TYPES, ReportType.TRAIN_EVENTS)
	}

	@Test
	fun enabledReportTypesContainsTrainContinuous() {
		assertContains(ShuntingLoop.ENABLED_REPORT_TYPES, ReportType.TRAIN_CONTINUOUS)
	}

	@Test
	fun enabledReportTypesContainsNodeEvents() {
		assertContains(ShuntingLoop.ENABLED_REPORT_TYPES, ReportType.NODE_EVENTS)
	}

	@Test
	fun enabledReportTypesHasFourEntries() {
		assertEquals(4, ShuntingLoop.ENABLED_REPORT_TYPES.size)
	}

	@Test
	fun enabledReportTypesDoesNotContainDebug() {
		assertTrue(
			ReportType._DEBUG !in ShuntingLoop.ENABLED_REPORT_TYPES,
			"Debug reports should not be enabled by default"
		)
	}

	@Test
	fun enabledReportTypesDoesNotContainPathSetting() {
		assertTrue(
			ReportType.PATH_SETTING !in ShuntingLoop.ENABLED_REPORT_TYPES,
			"PATH_SETTING is not enabled by ShuntingLoop scenario"
		)
	}
}
