/*
 * build.gradle.kts (root aggregator)
 *
 * Thin aggregator for interlockSim multi-module project.
 * Application code lives in :desktop-ui, domain code in :core.
 * This file owns: SonarQube config, checkKdisco, and delegation tasks.
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

    // 7.3.1 is the newest safe release, not the newest release. Do not bump blindly:
    //   7.0.0 - fixes the Gradle 9 cross-project configuration resolution error (#1000)
    //   7.2.0 to 7.2.2 - marked DO NOT UPGRADE by SonarSource (sources dropped from analysis)
    //   7.4.0 - open regression SCANGRADLE-441: sonarResolver loses task dependencies
    id("org.sonarqube") version "7.3.1.8318"
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
// Lifecycle delegation tasks
// ===========================================

tasks.register("test") {
    dependsOn(":core:jvmTest", ":desktop-ui:test", ":dispatcher-agent:test")
}

tasks.register("integrationTest") {
    dependsOn(":core:integrationTest", ":desktop-ui:integrationTest", ":dispatcher-agent:integrationTest")
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

        // Server coordinates. Neither value is a secret, so a local run needs only a token:
        //   SONAR_TOKEN=… ./gradlew sonar
        // CI still overrides these with -Dsonar.* system properties, which win over the extension.
        property("sonar.host.url", providers.gradleProperty("sonar.host.url").orElse("https://sonarcloud.io").get())
        property("sonar.organization", providers.gradleProperty("sonar.organization").orElse("bedahovorka").get())

        // Source and test paths (desktop-ui + :core KMP subproject).
        // :dispatcher-agent sources/tests are configured in dispatcher-agent/build.gradle.kts
        // via its own sonar {} block to avoid double-indexing (SonarQube Gradle plugin v6
        // auto-detects JVM subproject source sets; listing them here AND in the subproject
        // causes "can't be indexed twice" errors — Issue #762).
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

        // Read lazily through providers: the v7 plugin fails on eager gradle-property reads
        // inside this block (component2(...) must not be null). See issue #1000.
        property("sonar.java.source", providers.gradleProperty("javaVersion").get())
        property("sonar.java.target", providers.gradleProperty("javaVersion").get())
        property("sonar.kotlin.source.version", providers.gradleProperty("kotlinVersion").get())

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
