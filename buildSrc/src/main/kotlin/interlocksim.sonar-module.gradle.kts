/*
 * interlocksim.sonar-module — the report paths every analyzed module hands to Sonar
 * (Issue #1018).
 *
 * A module applies this plugin and fills the `sonarModule {}` extension. Keys that only one
 * module needs (binaries, libraries, sources, exclusions) stay in that module's own
 * `sonar {}` block.
 *
 * The org.sonarqube plugin itself is applied by the root project, which adds the `sonar`
 * extension to every subproject. Its `properties {}` actions run only when the root :sonar
 * task computes the analysis properties, so the reads below are lazy: nothing is read
 * eagerly from another project (Issue #1000).
 */

import org.sonarqube.gradle.SonarExtension

plugins {
	id("interlocksim.jacoco")
}

val sonarModule = extensions.create<SonarModuleExtension>("sonarModule")

extensions.getByName<SonarExtension>("sonar").properties {
	property(
		"sonar.junit.reportPaths",
		sonarModule.junitReportPaths.get().joinToString(","),
	)
	property(
		"sonar.coverage.jacoco.xmlReportPaths",
		listOf(
			sonarModule.coverageReport.get().asFile.absolutePath,
			project.extra["aggregatedCoverageReport"] as String,
		).joinToString(","),
	)
}
