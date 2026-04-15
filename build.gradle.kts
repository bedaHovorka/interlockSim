/*
 * build.gradle.kts (root aggregator)
 *
 * Thin aggregator for interlockSim multi-module project.
 * Application code lives in :desktop-ui, domain code in :core.
 * This file owns: SonarQube config, checkKdisco, and delegation tasks.
 */

plugins {
    // Declare versions for subprojects (apply false — subprojects opt in)
    kotlin("jvm") version "2.1.10" apply false
    kotlin("multiplatform") version "2.1.10" apply false
    id("com.gradleup.shadow") version "8.3.8" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
    id("me.champeau.jmh") version "0.7.2" apply false
    id("app.cash.burst") version "2.4.0" apply false  // keep in sync with burstVersion in gradle.properties
    id("dev.mokkery") version "2.7.3" apply false  // keep in sync with mokkeryVersion in gradle.properties

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
                "${System.getProperty("user.home")}/.m2/repository/cz/hovorka/kdisco/kdisco-core-jvm/$kdiscoVersion/kdisco-core-jvm-$kdiscoVersion.jar",
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
    dependsOn(":core:jvmTest", ":desktop-ui:test")
}

tasks.register("integrationTest") {
    dependsOn(":core:integrationTest", ":desktop-ui:integrationTest")
}

listOf(
    "runSim", "runEditor", "runExample", "runExampleGui", "runSimFromXml",
    "shadowJar", "verifyKoinConfiguration", "koinStatus", "printConfig",
).forEach { name ->
    tasks.register(name) { dependsOn(":desktop-ui:$name") }
}

tasks.register("buildFastSim") { dependsOn(":fast-sim:linkReleaseExecutableLinuxX64") }
tasks.register("runFastSim") { dependsOn(":fast-sim:runDebugExecutableLinuxX64") }
tasks.register("buildFastSimRelease") { dependsOn(":fast-sim:linkReleaseExecutableLinuxX64") }
tasks.register("runFastSimRelease") { dependsOn(":fast-sim:runReleaseExecutableLinuxX64") }

// ===========================================
// SonarQube Configuration
// ===========================================

sonar {
    properties {
        property("sonar.projectKey", "bedaHovorka_interlockSim")
        property("sonar.projectName", "interlockSim - Railway Interlocking Simulator")
        property("sonar.projectVersion", version.toString())

        // Source and test paths (desktop-ui + :core KMP subproject).
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
        property("sonar.java.binaries",  "desktop-ui/build/classes/kotlin/main,core/build/classes/kotlin/jvm/main")
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
                "core/build/test-results/integrationTest",
        )
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            "desktop-ui/build/reports/jacoco/test/jacocoTestReport.xml," +
                "core/build/reports/jacoco/jvmTest/jacocoTestReport.xml",
        )

        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.qualitygate.wait", "false")

        // :fast-sim compiles to linuxX64 native — JaCoCo cannot instrument native code.
        // :core-test is test-support infrastructure, not production code requiring coverage.
        property(
            "sonar.coverage.exclusions",
            "fast-sim/**,core-test/**," +
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
    )

    executionData.setFrom(
        fileTree("core/build").include("jacoco/*.exec"),
        fileTree("desktop-ui/build").include("jacoco/*.exec"),
    )
    sourceDirectories.setFrom(
        files("core/src/commonMain/kotlin", "core/src/jvmMain/kotlin", "desktop-ui/src/main/kotlin")
    )
    classDirectories.setFrom(
        fileTree("core/build/classes/kotlin/jvm/main"),
        fileTree("desktop-ui/build/classes/kotlin/main"),
    )

    reports {
        xml.required.set(true)
        xml.outputLocation.set(file("build/reports/jacoco/aggregated/jacocoTestReport.xml"))
        html.required.set(true)
        html.outputLocation.set(file("build/reports/jacoco/aggregated/html"))
        csv.required.set(false)
    }
}
