/*
 * interlocksim.jacoco — the JaCoCo engine pin and the aggregate report path (Issue #1018).
 *
 * Which tests feed a module's jacocoTestReport stays in the module's own script; this plugin
 * only pins the engine and publishes where the root jacocoAggregatedReport writes its XML.
 */

plugins {
	jacoco
}

jacoco {
	toolVersion = property("jacocoToolVersion") as String
}

// Absolute path to the root project's cross-module JaCoCo report. Absolute, because Sonar
// resolves a relative coverage path against THIS module's base directory.
extra["aggregatedCoverageReport"] =
	rootProject.layout.buildDirectory
		.file("reports/jacoco/aggregated/jacocoTestReport.xml")
		.get()
		.asFile.absolutePath
