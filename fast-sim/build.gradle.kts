/*
 * fast-sim/build.gradle.kts
 *
 * :fast-sim — native linuxX64 simulation CLI
 * Compiles to a statically-linked native binary with no JVM dependency.
 * Depends on :core (commonMain provides simulation engine, XML parsing, domain model).
 */

plugins {
	kotlin("multiplatform")
	id("io.gitlab.arturbosch.detekt")
	id("org.jlleitschuh.gradle.ktlint")
}

// Load versions from root gradle.properties
val kotlinVersion: String by project

group = "cz.vutbr.fit"
version = "1.0"

kotlin {
	// linuxX64 only — this is a native CLI binary, not a library
	linuxX64 {
		binaries {
			executable {
				entryPoint = "cz.vutbr.fit.interlockSim.fastsim.main"
				baseName = "fast-sim"
			}
		}
	}

	sourceSets {
		val linuxX64Main by getting {
			dependencies {
				// All simulation logic, XML parsing, Koin DI come transitively via :core
				implementation(project(":core"))
			}
		}
	}
}

// ===========================================
// checkKdisco dependency
// ===========================================

tasks.named("compileKotlinLinuxX64") {
	dependsOn(rootProject.tasks.named("checkKdisco"))
}

// ===========================================
// Detekt Configuration
// ===========================================

detekt {
	config.setFrom(files("${rootProject.projectDir}/detekt.yml"))
	buildUponDefaultConfig = true
	allRules = false
	source.setFrom("src/linuxX64Main/kotlin")
	ignoreFailures = false
	baseline = file("${rootProject.projectDir}/detekt-baseline.xml")
	parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
	jvmTarget = "21"
	reports {
		html.required.set(true)
		xml.required.set(true)
		txt.required.set(true)
		sarif.required.set(false)
		md.required.set(false)
	}
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
	jvmTarget = "21"
}

dependencies {
	detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.7")
}

// ===========================================
// Ktlint Configuration
// ===========================================

ktlint {
	version.set("1.5.0")
	verbose.set(true)
	outputToConsole.set(true)
	outputColorName.set("AUTO")
	enableExperimentalRules.set(false)
	android.set(false)
	filter {
		exclude("**/generated/**")
		exclude("**/build/**")
	}
	reporters {
		reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
		reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
	}
}

// Disabled for consistency with :core and :desktop-ui — see their build files for rationale.
tasks.matching { it.name.startsWith("ktlint") && it.name.endsWith("Check") }.configureEach {
	enabled = false
}
