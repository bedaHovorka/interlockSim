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
	id("app.cash.burst")
	id("dev.mokkery")
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
val coroutinesVersion: String by project
val xmlutilVersion: String by project

group = "cz.vutbr.fit"
version = "1.0"

// commonMain is KMP-clean: no java.*/javax.* imports, no JVM-only idioms.
// JVM-only code (xml/, context factories) lives in jvmMain.
// linuxX64 target added for native compilation verification.
kotlin {
	jvm {
		compilations.all {
			kotlinOptions {
				jvmTarget = "21"
				freeCompilerArgs += "-Xexpect-actual-classes"
			}
		}
		testRuns["test"].executionTask.configure {
			// Use desktop-ui dir so relative File paths like "src/test/resources/..." resolve correctly
			workingDir = rootProject.projectDir.resolve("desktop-ui")
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

	// linuxX64 target: runs native commonTest subset (NativeSanityTest).
	// kDisco 0.3.0 ships a linuxX64 klib; kotlinx-coroutines-core, koin-core, assertk all have native variants.
	// Note: KMP automatically creates a debugTest binary for linuxX64 — no explicit binaries.test() needed.
	linuxX64 {
		compilations["main"].cinterops {
			create("libxml2") {
				defFile = file("src/nativeInterop/cinterop/libxml2.def")
			}
		}
	}

	sourceSets {
		val commonMain by getting {
			dependencies {
				// KMP multiplatform artifacts (jvm + linuxX64 klibsavailable in mavenLocal)
				implementation("cz.hovorka.kdisco:kdisco-core:$kdiscoVersion")
				implementation("io.insert-koin:koin-core:$koinVersion")
				implementation("io.github.oshai:kotlin-logging:$kotlinLoggingVersion")
				implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
				// runBlocking needed for DefaultSimulationContext (bridging suspend Simulation.run())
				implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
				implementation("io.github.pdvrieze.xmlutil:core:$xmlutilVersion")
			}
		}
		val commonTest by getting {
			dependencies {
				implementation(kotlin("test"))
				implementation("com.willowtreeapps.assertk:assertk:$assertkVersion")
				implementation("io.insert-koin:koin-test:$koinVersion")
			}
		}
		val jvmMain by getting {
			dependencies {
				// kotlin-reflect is JVM-only; not needed in KMP commonMain
				implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
				// SLF4J API for JVM (logging backend wiring)
				implementation("org.slf4j:slf4j-api:$slf4jVersion")
			}
		}
		val jvmTest by getting {
			dependencies {
				implementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
				implementation("org.junit.jupiter:junit-jupiter-params:$junitJupiterVersion")
				implementation("io.mockk:mockk:$mockkVersion")
				implementation("io.insert-koin:koin-test-junit5:$koinVersion")
				runtimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
				runtimeOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")
				runtimeOnly("org.junit.platform:junit-platform-console:$junitPlatformVersion")
				runtimeOnly("ch.qos.logback:logback-classic:$logbackVersion")
			}
		}
	}
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
					println("\n:core linuxX64 Test Results: ${result.resultType}")
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
// Integration Test Task
// ===========================================

val integrationTest by tasks.registering(Test::class) {
	group = "verification"
	description = "Run :core integration tests (tagged with @Tag(\"integration-test\"))"

	// Use desktop-ui dir so relative File paths like "src/test/resources/..." resolve correctly
	workingDir = rootProject.projectDir.resolve("desktop-ui")

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
// SonarQube Configuration
// ===========================================

// Suppress automatic SonarQube sub-module for :core.
// The root sonar{} block explicitly registers all :core source/binary/report paths,
// so letting the plugin auto-discover them here causes "can't be indexed twice".
// Note: sonar.skip is a boolean field (isSkipProject), not a string property.
sonarqube {
	isSkipProject = true
}

// ===========================================
// JaCoCo Configuration
// ===========================================

jacoco {
	toolVersion = "0.8.11"
}

val jacocoTestReport by tasks.registering(JacocoReport::class) {
	dependsOn(tasks.named("jvmTest"))

	executionData.setFrom(
		fileTree(layout.buildDirectory).include("jacoco/jvmTest.exec")
	)
	sourceDirectories.setFrom(
		files("src/commonMain/kotlin", "src/jvmMain/kotlin")
	)
	classDirectories.setFrom(
		fileTree("${layout.buildDirectory.get()}/classes/kotlin/jvm/main")
	)

	reports {
		xml.required.set(true)
		xml.outputLocation.set(file("${layout.buildDirectory.get()}/reports/jacoco/jvmTest/jacocoTestReport.xml"))
		html.required.set(true)
		html.outputLocation.set(file("${layout.buildDirectory.get()}/reports/jacoco/jvmTest/html"))
		csv.required.set(false)
	}
}

tasks.named("jvmTest") {
	finalizedBy(jacocoTestReport)
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

// Disable ktlint checks — project-wide policy (desktop-ui has the same disable block).
// Reason: legacy Java→Kotlin converted code uses tabs (per .editorconfig) which conflicts
// with ktlint's default spaces rule. Detekt handles structural quality checks instead.
// TODO: Re-enable ktlint project-wide once tab/space configuration is resolved (tracked).
tasks.matching { it.name.startsWith("ktlint") && it.name.endsWith("Check") }.configureEach {
	enabled = false
}

// ===========================================
// checkKdisco dependency
// ===========================================

tasks.named("compileKotlinJvm") {
	dependsOn(rootProject.tasks.named("checkKdisco"))
}

// ===========================================
// CommonMain Purity Gate
// ===========================================
// Ensures commonMain stays free of JVM-only imports (java.*, javax.*) and idioms.
// This is required for adding non-JVM KMP targets in the future.

val checkCoreCommonMainPurity by tasks.registering {
	group = "verification"
	description = "Verify :core commonMain contains no java.* or javax.* imports"

	val commonMainDir = file("src/commonMain/kotlin")

	doLast {
		if (!commonMainDir.exists()) {
			logger.info("commonMain directory {} does not exist, skipping purity check.", commonMainDir)
			return@doLast
		}

		// Catch import-level java.*/javax.* references
		val importRegex = Regex("^import\\s+(java|javax)\\..*")
		// Catch inline fully-qualified java.* references (e.g. java.util.TreeSet, java.lang.*)
		val inlineJavaRegex = Regex("(?<![\\w])java\\.[a-z]")
		// Catch System.* calls (java.lang.System is implicitly available on JVM only)
		val systemRegex = Regex("(?<![\\w.])System\\.[a-zA-Z]")
		val violations = mutableListOf<String>()

		project.fileTree(commonMainDir).matching { include("**/*.kt") }.forEach { file ->
			val lines = file.readLines()
			lines.forEachIndexed { index, line ->
				val trimmed = line.trim()
				// Skip comment lines
				if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
				val hasViolation = importRegex.containsMatchIn(line) ||
					inlineJavaRegex.containsMatchIn(line) ||
					systemRegex.containsMatchIn(line)
				if (hasViolation) {
					if (violations.isEmpty() || violations.last() != file.path) {
						println("PURITY VIOLATION: ${file.path}")
						violations.add(file.path)
					}
					println("${index + 1}: $line")
				}
			}
		}

		if (violations.isNotEmpty()) {
			throw org.gradle.api.GradleException(
				"ERROR: commonMain contains JVM-only code. Move java.*/System.* usages to jvmMain."
			)
		}

		println("commonMain purity check passed - no JVM-only code found.")
	}
}

tasks.named("check") {
	dependsOn(checkCoreCommonMainPurity)
}
