/*
 * core/build.gradle.kts
 *
 * Gradle build configuration for interlockSim :core module
 * Uses Kotlin Multiplatform (JVM-only target initially)
 * Extracts domain, context, objects, sim, util, exceptions packages
 */

plugins {
	kotlin("multiplatform")
	id("io.gitlab.arturbosch.detekt")
	id("org.jlleitschuh.gradle.ktlint")
	jacoco
}

// Load versions from root gradle.properties
val kdiscoVersion: String by project
val slf4jVersion: String by project
val logbackVersion: String by project
val kotlinLoggingVersion: String by project
val kotlinVersion: String by project
val junitJupiterVersion: String by project
val junitPlatformVersion: String by project
val assertkVersion: String by project
val mockkVersion: String by project
val koinVersion: String by project

group = "cz.vutbr.fit"
version = "1.0"

kotlin {
	jvm {
		compilations.all {
			kotlinOptions {
				jvmTarget = "21"
			}
		}
		testRuns["test"].executionTask.configure {
			// Use root project dir so relative File paths like "src/test/resources/..." resolve correctly
			workingDir = rootProject.projectDir
			useJUnitPlatform {
				excludeTags("integration-test")
			}
			maxParallelForks = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
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
							println("\n:core Test Results: ${result.resultType}")
							println("  Tests run: ${result.testCount}")
							println("  Passed: ${result.successfulTestCount}")
							println("  Failed: ${result.failedTestCount}")
							println("  Skipped: ${result.skippedTestCount}")
						}
					}),
				)
			}
		}
	}

	sourceSets {
		val commonMain by getting {
			dependencies {
				implementation("cz.hovorka.kdisco:kdisco-core-api-jvm:$kdiscoVersion")
				implementation("io.insert-koin:koin-core:$koinVersion")
				implementation("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")
				implementation("org.slf4j:slf4j-api:$slf4jVersion")
				implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
				implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
			}
		}
		val commonTest by getting {
			dependencies {
				implementation(kotlin("test"))
				implementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
				implementation("org.junit.jupiter:junit-jupiter-params:$junitJupiterVersion")
				implementation("com.willowtreeapps.assertk:assertk:$assertkVersion")
				implementation("io.mockk:mockk:$mockkVersion")
				implementation("io.insert-koin:koin-test:$koinVersion")
				implementation("io.insert-koin:koin-test-junit5:$koinVersion")
			}
		}
		val jvmMain by getting
		val jvmTest by getting {
			dependencies {
				runtimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
				runtimeOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")
				runtimeOnly("org.junit.platform:junit-platform-console:$junitPlatformVersion")
				runtimeOnly("ch.qos.logback:logback-classic:$logbackVersion")
			}
		}
	}
}

// ===========================================
// Integration Test Task
// ===========================================

val integrationTest by tasks.registering(Test::class) {
	group = "verification"
	description = "Run :core integration tests (tagged with @Tag(\"integration-test\"))"

	// Use root project dir so relative File paths like "src/main/resources/..." resolve correctly
	workingDir = rootProject.projectDir

	useJUnitPlatform {
		includeTags("integration-test")
	}

	maxParallelForks = 1

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
					println("\n:core Integration Test Results: ${result.resultType}")
					println("  Tests run: ${result.testCount}")
					println("  Passed: ${result.successfulTestCount}")
					println("  Failed: ${result.failedTestCount}")
					println("  Skipped: ${result.skippedTestCount}")
				}
			}),
		)
	}

	reports {
		junitXml.required.set(true)
		junitXml.outputLocation.set(file("${layout.buildDirectory.get()}/test-results/integrationTest"))
		html.required.set(true)
		html.outputLocation.set(file("${layout.buildDirectory.get()}/reports/tests/integrationTest"))
	}

	ignoreFailures = false

	testClassesDirs = kotlin.jvm().compilations["test"].output.classesDirs
	classpath = kotlin.jvm().compilations["test"].runtimeDependencyFiles
}

// ===========================================
// JaCoCo Configuration
// ===========================================

jacoco {
	toolVersion = "0.8.11"
}

tasks.named("jvmTest") {
	finalizedBy(tasks.matching { it.name == "jacocoTestReport" })
}

tasks.withType<JacocoReport>().configureEach {
	dependsOn(tasks.named("jvmTest"))

	reports {
		xml.required.set(true)
		xml.outputLocation.set(file("${layout.buildDirectory.get()}/reports/jacoco/jvmTest/jacocoTestReport.xml"))
		html.required.set(true)
		html.outputLocation.set(file("${layout.buildDirectory.get()}/reports/jacoco/jvmTest/html"))
		csv.required.set(false)
	}
}

// ===========================================
// Detekt Configuration
// ===========================================

detekt {
	config.setFrom(files("${rootProject.projectDir}/detekt.yml"))
	buildUponDefaultConfig = true
	allRules = false
	source.setFrom(
		"src/commonMain/kotlin",
		"src/commonTest/kotlin",
		"src/jvmMain/kotlin",
		"src/jvmTest/kotlin",
	)
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

// Disable ktlint checks (matching app module policy)
tasks.matching { it.name.startsWith("ktlint") && it.name.endsWith("Check") }.configureEach {
	enabled = false
}

// ===========================================
// checkKdisco dependency
// ===========================================

tasks.named("compileKotlinJvm") {
	dependsOn(rootProject.tasks.named("checkKdisco"))
}
