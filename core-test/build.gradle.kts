/*
 * core-test/build.gradle.kts
 *
 * :core-test — shared test-support library for interlockSim
 *
 * Contains test fixtures and utilities shared across :core, :desktop-ui, and :fast-sim.
 * Lives in commonMain (not test source sets) so it is accessible from test scopes
 * in consuming modules — core/commonTest, desktop-ui/test, and fast-sim/linuxX64Test.
 *
 * KMP targets: jvm (for :desktop-ui tests, :core jvmTest), linuxX64 (for :fast-sim)
 */

plugins {
	kotlin("multiplatform")
	id("io.gitlab.arturbosch.detekt")
}

// Load versions from root gradle.properties
val assertkVersion: String by project
val koinVersion: String by project
val kotlinVersion: String by project
val kdiscoVersion: String by project
val detektFormattingVersion: String by project

group = "cz.vutbr.fit"
version = "1.0"

val isLinuxHost: Boolean by gradle.extra

kotlin {
	// Applies to every compilation (metadata, jvm, linuxX64).
	compilerOptions {
		// Issue #713: the build is warning-free as of this commit. A new warning is a
		// regression, so fail on it. If a Kotlin version bump introduces a brand-new
		// warning kind, fix the warning — do not turn this flag back off.
		allWarningsAsErrors.set(true)
	}

	jvm {
		compilerOptions {
			jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
		}
		testRuns["test"].executionTask.configure {
			useJUnitPlatform()
		}
	}

	if (isLinuxHost) {
		linuxX64()
	}

	sourceSets {
		val commonMain by getting {
			dependencies {
				// :core commonMain provides domain classes, XmlContextReader, xmlutil, kdisco
				implementation(project(":core"))
				// koin-core: needed for CommonCoreTestModule (scoped, getSource, module DSL)
				// (:core uses implementation(koin-core) which is not transitive to consumers)
				implementation("io.insert-koin:koin-core:$koinVersion")
				// assertk: api so consumers (core, desktop-ui, fast-sim) get it transitively
				api("com.willowtreeapps.assertk:assertk:$assertkVersion")
				// kotlin.test: provides @BeforeTest/@AfterTest for CommonKoinTestBase;
				// commonMain (not commonTest) because core-test is a test-support library
				// consumed from test source sets of other modules.
				implementation(kotlin("test"))
				// kdisco: ShuntingLoopRuns and NavigationDecoratingContext name kDisco types
				// (Process, Condition) in commonMain signatures. :core declares kdisco with
				// implementation(), so it does not reach this module transitively.
				implementation("cz.ksimulantenbande.kdisco:kdisco-core:$kdiscoVersion")
			}
		}
		val jvmMain by getting {
			dependencies {
				// kotlin-test-junit5: resolves @BeforeTest/@AfterTest on the JVM target;
				// the base kotlin-test JVM artifact alone does not export these typealiases
				// when compiled outside a test source set — the junit5 variant makes them
				// available for commonMain JVM compilation of CommonKoinTestBase.
				implementation(kotlin("test-junit5"))
			}
		}
	}
}

// ===========================================
// checkKdisco dependency
// ===========================================

tasks.named("compileKotlinJvm") {
	dependsOn(rootProject.tasks.named("checkKdisco"))
}

if (isLinuxHost) {
	tasks.named("compileKotlinLinuxX64") {
		dependsOn(rootProject.tasks.named("checkKdisco"))
	}
}

// ===========================================
// Detekt Configuration
// ===========================================

// :core-test holds legacy-adjacent test support code; use permissive detekt.yml (not strict).
detekt {
	config.setFrom(files("${rootProject.projectDir}/detekt.yml"))
	buildUponDefaultConfig = true
	allRules = false
	source.setFrom("src/commonMain/kotlin")
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
	detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:$detektFormattingVersion")
}

// Suppress automatic SonarQube sub-module detection for :core-test.
// Requires the org.sonarqube plugin applied at root (build.gradle.kts) via id("org.sonarqube").
// Test support code is excluded from coverage requirements via root sonar.coverage.exclusions = "core-test/**".
sonar {
	isSkipProject = true
}
