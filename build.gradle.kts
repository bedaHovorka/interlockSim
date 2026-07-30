/*
 * build.gradle.kts (root aggregator)
 *
 * Thin aggregator for interlockSim multi-module project.
 * Application code lives in :desktop-ui, domain code in :core.
 * This file owns: SonarQube config, checkKdisco, checkCoreUntouchedBySp2c, and delegation tasks.
 */

plugins {
    // Declare versions for subprojects (apply false — subprojects opt in).
    // kotlin/ktlint/detekt/burst/mokkery versions all come from gradle.properties
    // via pluginManagement in settings.gradle.kts.
    kotlin("jvm") apply false
    kotlin("multiplatform") apply false
    id("com.gradleup.shadow") version "8.3.8" apply false
    id("org.jlleitschuh.gradle.ktlint") apply false
    id("io.gitlab.arturbosch.detekt") apply false
    id("me.champeau.jmh") version "0.7.2" apply false
    id("app.cash.burst") apply false
    id("dev.mokkery") apply false

    id("org.sonarqube") version "6.2.0.5505"
    jacoco
}

// Load versions from gradle.properties
val kdiscoVersion: String by project
val slf4jVersion: String by project
val logbackVersion: String by project
val kotlinLoggingVersion: String by project
val junitPlatformVersion: String by project
val junitJupiterVersion: String by project
val assertkVersion: String by project
val mockkVersion: String by project
val koinVersion: String by project
val javaVersion: String by project
val kotlinVersion: String by project

group = "cz.vutbr.fit"
version = "1.0"

// ===========================================
// checkKdisco — must live here so both subprojects can depend on it
// ===========================================

val checkKdisco by tasks.registering {
    group = "verification"
    description = "Verify kDisco library is installed in Maven local repository"

    doLast {
        val kdiscoJar =
            file(
                "${System.getProperty("user.home")}/.m2/repository/cz/ksimulantenbande/kdisco/kdisco-core-jvm/$kdiscoVersion/kdisco-core-jvm-$kdiscoVersion.jar",
            )

        if (kdiscoJar.exists()) {
            println("✓ kDisco $kdiscoVersion found in mavenLocal: ${kdiscoJar.absolutePath}")
        } else {
            println("⚠ kDisco $kdiscoVersion not found in mavenLocal")
            println("  To install: cd ~/work/kdisco && ./gradlew :kdisco-core:publishToMavenLocal")
        }
    }
}

// ===========================================
// SP2c.0: :core immutability guard (Issue #823, #822 constraint C10 / principle P9)
// ===========================================
//
// Fails the build if any file under core/ differs from a frozen SP2c-start baseline commit,
// unless explicitly allowlisted — see gradle/sp2c-core-baseline.properties and
// gradle/sp2c-core-allowlist.txt, and docs/KOTLIN_STYLE_GUIDE.md ("SP2c :core Immutability
// Guard") for the exception process. Diffing logic lives in buildSrc's Sp2cCoreGuard so it is
// unit-testable in isolation (see buildSrc/src/test/kotlin/Sp2cCoreGuardTest.kt).

val checkCoreUntouchedBySp2c by tasks.registering {
    group = "verification"
    description = "Fail if any file under core/ changed relative to the SP2c baseline without an allowlist entry"

    // The result depends on live git history (current HEAD), which Gradle's up-to-date
    // checking cannot observe from declared task inputs — never treat this as cacheable.
    outputs.upToDateWhen { false }

    doLast {
        val baselinePropertiesFile = rootProject.file("gradle/sp2c-core-baseline.properties")
        if (!baselinePropertiesFile.exists()) {
            throw org.gradle.api.GradleException(
                "checkCoreUntouchedBySp2c misconfigured: missing ${baselinePropertiesFile.path}",
            )
        }
        val baselineProperties = java.util.Properties()
        baselinePropertiesFile.inputStream().use { baselineProperties.load(it) }
        val baselineRef =
            baselineProperties.getProperty("baselineRef")?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw org.gradle.api.GradleException(
                    "checkCoreUntouchedBySp2c misconfigured: 'baselineRef' missing from ${baselinePropertiesFile.path}",
                )

        val allowlistFile = rootProject.file("gradle/sp2c-core-allowlist.txt")
        val allowlistedPaths =
            if (allowlistFile.exists()) {
                Sp2cCoreGuard.parseAllowlist(allowlistFile.readLines())
            } else {
                emptySet()
            }

        when (val result = Sp2cCoreGuard.evaluate(rootDir, baselineRef, allowlistedPaths)) {
            is Sp2cCoreGuard.Result.Skipped -> {
                logger.lifecycle("checkCoreUntouchedBySp2c: SKIPPED — ${result.reason}")
            }
            is Sp2cCoreGuard.Result.Passed -> {
                logger.lifecycle("checkCoreUntouchedBySp2c: PASSED — no core/ changes relative to baseline ${result.baselineRef}.")
            }
            is Sp2cCoreGuard.Result.Violated -> {
                logger.error(
                    "checkCoreUntouchedBySp2c: VIOLATION — the following core/ file(s) changed relative to " +
                        "baseline ${result.baselineRef} without an allowlist entry:",
                )
                result.offendingFiles.forEach { logger.error("  $it") }
                throw org.gradle.api.GradleException(
                    "checkCoreUntouchedBySp2c failed: ${result.offendingFiles.size} file(s) under core/ changed " +
                        "relative to baseline ${result.baselineRef} without an allowlist entry: " +
                        "${result.offendingFiles.joinToString(", ")}. See gradle/sp2c-core-allowlist.txt and " +
                        "docs/KOTLIN_STYLE_GUIDE.md (\"SP2c :core Immutability Guard\") for the exception process " +
                        "(Issue #822 C10/P9).",
                )
            }
        }
    }
}

// ===========================================
// Lifecycle delegation tasks
// ===========================================

tasks.register("test") {
    dependsOn(":core:jvmTest", ":desktop-ui:test", ":dispatcher-agent:test")
}

tasks.register("integrationTest") {
    dependsOn(":core:integrationTest", ":desktop-ui:integrationTest", ":dispatcher-agent:integrationTest")
}

// The root project applies no `base`/lifecycle-base plugin of its own, so (unlike `test` and
// `integrationTest` above) there is no pre-existing root `check` task to hook into — running
// `./gradlew check` from the repo root only triggers each subproject's own `check` task via
// Gradle's default multi-project task-name matching. Registering a root `check` here adds
// checkCoreUntouchedBySp2c to that set without displacing the existing per-subproject checks.
tasks.register("check") {
    group = "verification"
    description = "Root aggregate check task; also runs checkCoreUntouchedBySp2c (Issue #823)"
    dependsOn(checkCoreUntouchedBySp2c)
}

listOf(
    "runSim", "runEditor", "runExample", "runExampleGui", "runSimFromXml",
    "shadowJar", "verifyKoinConfiguration", "koinStatus", "printConfig",
).forEach { name ->
    tasks.register(name) { dependsOn(":desktop-ui:$name") }
}

// :fast-sim and native subprojects are only included on Linux hosts; guard lifecycle tasks accordingly.
// Defined in settings.gradle.kts and shared via gradle.extra.
val isLinuxHost: Boolean by gradle.extra
if (isLinuxHost) {
	tasks.register("buildFastSim") { dependsOn(":fast-sim:linkReleaseExecutableLinuxX64") }
	tasks.register("runFastSim") { dependsOn(":fast-sim:runDebugExecutableLinuxX64") }
	tasks.register("buildFastSimRelease") { dependsOn(":fast-sim:linkReleaseExecutableLinuxX64") }
	tasks.register("runFastSimRelease") { dependsOn(":fast-sim:runReleaseExecutableLinuxX64") }
}

// ===========================================
// SonarQube Configuration
// ===========================================

sonar {
    properties {
        property("sonar.projectKey", "bedaHovorka_interlockSim")
        property("sonar.projectName", "interlockSim - Railway Interlocking Simulator")
        property("sonar.projectVersion", version.toString())

        // Source and test paths (desktop-ui + :core KMP subproject).
        // :dispatcher-agent sources/tests are configured in dispatcher-agent/build.gradle.kts
        // via its own sonar {} block to avoid double-indexing (SonarQube Gradle plugin v6
        // auto-detects JVM subproject source sets; listing them here AND in the subproject
        // causes "can't be indexed twice" errors — Issue #762).
        // Kept in sync with sonar-project.properties (used for local sonar-scanner runs).
        property(
            "sonar.sources",
            "desktop-ui/src/main/kotlin,core/src/commonMain/kotlin," +
                "core/src/jvmMain/kotlin,core/src/nativeMain/kotlin",
        )
        property(
            "sonar.tests",
            "desktop-ui/src/test/kotlin,core/src/commonTest/kotlin,core/src/jvmTest/kotlin",
        )
        property("sonar.java.binaries", "desktop-ui/build/classes/kotlin/main,core/build/classes/kotlin/jvm/main")
        property("sonar.java.test.binaries", "desktop-ui/build/classes/kotlin/test,core/build/classes/kotlin/jvm/test")

        property("sonar.java.source", javaVersion)
        property("sonar.java.target", javaVersion)
        property("sonar.language", "java,kotlin")
        property("sonar.kotlin.source.version", kotlinVersion)

        property(
            "sonar.junit.reportPaths",
            "desktop-ui/build/test-results/test," +
                "desktop-ui/build/test-results/integrationTest," +
                "core/build/test-results/jvmTest," +
                "core/build/test-results/integrationTest," +
                "dispatcher-agent/build/test-results/test," +
                "dispatcher-agent/build/test-results/integrationTest",
        )
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            listOf(
                file("desktop-ui/build/reports/jacoco/test/jacocoTestReport.xml"),
                file("core/build/reports/jacoco/jvmTest/jacocoTestReport.xml"),
                file("dispatcher-agent/build/reports/jacoco/test/jacocoTestReport.xml"),
            ).joinToString(",") { it.absolutePath },
        )

        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.qualitygate.wait", "false")

        // :fast-sim and :core's nativeMain compile to linuxX64 native — JaCoCo cannot
        // instrument native code (coverage comes from :core:linuxX64Test).
        // :core-test is test-support infrastructure, not production code requiring coverage.
        property(
            "sonar.coverage.exclusions",
            "fast-sim/**,core-test/**,core/src/nativeMain/**," +
                "desktop-ui/src/main/kotlin/**/gui/MenuBar.kt," +
                "desktop-ui/src/main/kotlin/**/gui/Frame.kt," +
                "desktop-ui/src/main/kotlin/**/gui/RailwayNetGridCanvas.kt," +
                "desktop-ui/src/main/kotlin/**/gui/ToolBar.kt," +
                "desktop-ui/src/main/kotlin/**/gui/ValidationDialog.kt," +
                "desktop-ui/src/main/kotlin/**/gui/RenameDialog.kt," +
                "desktop-ui/src/main/kotlin/**/gui/action/**," +
                "desktop-ui/src/main/kotlin/**/gui/gridcanvas/**," +
                "desktop-ui/src/main/kotlin/**/gui/animation/**",
        )
    }
}

tasks.named("sonar") {
    dependsOn(
        ":desktop-ui:test", ":desktop-ui:integrationTest", ":desktop-ui:jacocoTestReport",
        ":core:jvmTest", ":core:integrationTest", ":core:jacocoTestReport",
        ":dispatcher-agent:test", ":dispatcher-agent:integrationTest", ":dispatcher-agent:jacocoTestReport",
    )
}

// ===========================================
// Aggregated JaCoCo Report (cross-module)
// ===========================================

val jacocoAggregatedReport by tasks.registering(JacocoReport::class) {
    group = "verification"
    description = "Generate aggregated JaCoCo coverage report across all modules"

    dependsOn(
        ":core:jvmTest", ":core:integrationTest",
        ":desktop-ui:test", ":desktop-ui:integrationTest",
        ":dispatcher-agent:test", ":dispatcher-agent:integrationTest",
    )

    executionData.setFrom(
        fileTree("core/build").include("jacoco/*.exec"),
        fileTree("desktop-ui/build").include("jacoco/*.exec"),
        fileTree("dispatcher-agent/build").include("jacoco/*.exec"),
    )
    sourceDirectories.setFrom(
        files("core/src/commonMain/kotlin", "core/src/jvmMain/kotlin", "desktop-ui/src/main/kotlin", "dispatcher-agent/src/main/kotlin")
    )
    classDirectories.setFrom(
        fileTree("core/build/classes/kotlin/jvm/main"),
        fileTree("desktop-ui/build/classes/kotlin/main"),
        fileTree("dispatcher-agent/build/classes/kotlin/main"),
    )

    reports {
        xml.required.set(true)
        xml.outputLocation.set(file("build/reports/jacoco/aggregated/jacocoTestReport.xml"))
        html.required.set(true)
        html.outputLocation.set(file("build/reports/jacoco/aggregated/html"))
        csv.required.set(false)
    }
}
