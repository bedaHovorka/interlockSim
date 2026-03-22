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
				// :core commonMain uses KMP artifacts with linuxX64 klibs:
				// koin-core 3.5.6, kdisco-core 0.3.0, xmlutil:core 0.91.0-RC1,
				// kotlin-logging 7.0.3 — all validated by :core:compileKotlinLinuxX64 passing.
				implementation(project(":core"))
			}
		}
		val linuxX64Test by getting {
			dependencies {
				implementation(kotlin("test"))
				// :core-test provides CommonTestFixtures, NetworkResources, CommonCoreTestModule
				implementation(project(":core-test"))
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
// linuxX64 Test Output Configuration
// ===========================================

tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>("linuxX64Test") {
	testLogging {
		events("passed", "skipped", "failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
		showExceptions = true
		showCauses = true
		showStackTraces = true
		showStandardStreams = false
		afterSuite(
			KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
				if (desc.parent == null) {
					println("\n:fast-sim linuxX64 Test Results: ${result.resultType}")
					println("  Tests run: ${result.testCount}")
					println("  Passed: ${result.successfulTestCount}")
					println("  Failed: ${result.failedTestCount}")
					println("  Skipped: ${result.skippedTestCount}")
				}
			}),
		)
	}
}

// ===========================================
// Detekt Configuration
// ===========================================

// :fast-sim is entirely new Kotlin code (not converted from Java), so detekt-strict.yml applies.
// See CLAUDE.md: "detekt-strict.yml — strict rules for new Kotlin code written from scratch".
detekt {
	config.setFrom(files("${rootProject.projectDir}/detekt-strict.yml"))
	buildUponDefaultConfig = true
	allRules = false
	source.setFrom("src/linuxX64Main/kotlin")
	ignoreFailures = false
	baseline = file("${rootProject.projectDir}/detekt-baseline.xml")
	parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
	// Detekt runs on the JVM as a static analysis tool regardless of the compilation
	// target. jvmTarget here refers to Detekt's analysis engine, not the native binary.
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

// Disable ktlint checks — project-wide policy (:core and :desktop-ui have the same disable block).
// Reason: tabs per .editorconfig conflict with ktlint's default spaces rule.
// Detekt handles structural quality checks instead.
// TODO: Re-enable ktlint project-wide once tab/space configuration is resolved (tracked).
tasks.matching { it.name.startsWith("ktlint") && it.name.endsWith("Check") }.configureEach {
	enabled = false
}
