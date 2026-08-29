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

        // Sources, tests, binaries, test reports and coverage reports are NOT listed here.
        // Every analyzed module declares its own in its own build script, so this file no
        // longer duplicates the module layout (Issue #699). Letting the plugin see each
        // module also lets it auto-detect sonar.java.libraries — the dependency classpath
        // the Kotlin analyzer needs for type resolution. While :core and :desktop-ui were
        // skipped with isSkipProject, they had no classpath at all and every Kotlin rule
        // that needs type information was degraded on them.
        //
        // sonar.java.source / sonar.java.target are gone too: this project has zero .java
        // files, so the Java sensor never runs and both properties did nothing.
        //
        // Read lazily through a provider: the v7 plugin fails on eager gradle-property reads
        // inside this block (component2(...) must not be null). See issue #1000.
        // major.minor only — the analyzer does not know patch versions and warns
        // "Failed to find Kotlin version '2.3.20'. Defaulting to 2.4" if given the full one.
        property(
            "sonar.kotlin.source.version",
            providers.gradleProperty("kotlinVersion").get().substringBeforeLast('.'),
        )

        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.qualitygate.wait", "false")

        // Coverage exclusions live in the module they belong to. A pattern set here is
        // inherited by every module and matched against the MODULE-relative path, so a
        // repo-relative pattern such as "desktop-ui/src/main/kotlin/**/gui/Frame.kt" would
        // silently never match inside :desktop-ui, where the path is "src/main/kotlin/...".
        //
        // :fast-sim and :core-test need no entry at all: neither is analyzed (both set
        // isSkipProject = true), so there is nothing to exclude.
    }
}

tasks.named("sonar") {
    dependsOn(
        ":desktop-ui:test", ":desktop-ui:integrationTest", ":desktop-ui:jacocoTestReport",
        ":core:jvmTest", ":core:integrationTest", ":core:jacocoTestReport",
        ":dispatcher-agent:test", ":dispatcher-agent:integrationTest", ":dispatcher-agent:jacocoTestReport",
        // Writes :core's jvm compile classpath for the Kotlin analyzer. Cheap, and it must run
        // even when the test tasks are skipped, so CI's -x flags never drop it.
        ":core:sonarJavaLibraries",
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
