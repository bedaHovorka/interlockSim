/*
 * interlocksim.ktlint — the ktlint setup every Kotlin module shares (Issue #1018).
 *
 * The engine version comes from gradle.properties (ktlintVersion) instead of floating with
 * the plugin default, so every module checks with the same engine. A module may add a
 * reporter or its own task exclusions on top (:desktop-ui does both).
 */

import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
	id("org.jlleitschuh.gradle.ktlint")
}

ktlint {
	version.set(property("ktlintVersion") as String)
	verbose.set(true)
	outputToConsole.set(true)
	enableExperimentalRules.set(false)
	android.set(false)
	filter {
		exclude("**/generated/**")
		exclude("**/build/**")
	}
	reporters {
		reporter(ReporterType.PLAIN)
		reporter(ReporterType.CHECKSTYLE)
	}
}
