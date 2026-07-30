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
val jacocoToolVersion: String by project

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
    group = "verification"
    description = "Run the unit tests of every JVM subproject"
    dependsOn(":core:jvmTest", ":desktop-ui:test", ":dispatcher-agent:test")
}

tasks.register("integrationTest") {
    group = "verification"
    description = "Run the integration tests of every JVM subproject"
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
    tasks.register(name) {
        group = "application"
        description = "Delegate to :desktop-ui:$name"
        dependsOn(":desktop-ui:$name")
    }
}

// :fast-sim and native subprojects are only included on Linux hosts; guard lifecycle tasks accordingly.
// Defined in settings.gradle.kts and shared via gradle.extra.
val isLinuxHost: Boolean by gradle.extra
if (isLinuxHost) {
	listOf(
		Triple("buildFastSim", "build", ":fast-sim:linkReleaseExecutableLinuxX64"),
		Triple("runFastSim", "application", ":fast-sim:runDebugExecutableLinuxX64"),
		Triple("buildFastSimRelease", "build", ":fast-sim:linkReleaseExecutableLinuxX64"),
		Triple("runFastSimRelease", "application", ":fast-sim:runReleaseExecutableLinuxX64"),
	).forEach { (name, taskGroup, target) ->
		tasks.register(name) {
			group = taskGroup
			description = "Delegate to $target"
			dependsOn(target)
		}
	}
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
        // A -Dsonar.host.url / -Dsonar.organization system property overrides either value
        // at scan time (see docs/KOTLIN_STYLE_GUIDE.md, "Running SonarQube Analysis").
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.organization", "bedahovorka")

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

// CI reuses the compiled classes, test results and coverage reports that the build
// workflow already produced, so it must not re-run any of that. It used to cancel the
// dependencies with nine -x flags in sonarqube.yml — a second copy of the module layout
// that had to be kept in step with this list by hand (issue #699). One flag replaces them:
//
//     ./gradlew sonar -PsonarReuseOutputs=true
//
// The value is read, not just its presence: a bare -PsonarReuseOutputs would set an empty
// string, which is invisible in a build log. getOrElse("false").toBoolean() also fails
// safe — a typo makes the tasks run, which is slower but never a silently empty analysis.
val sonarReuseOutputs = providers.gradleProperty("sonarReuseOutputs").getOrElse("false").toBoolean()

tasks.named("sonar") {
    // Always: writes :core's jvm compile classpath for the Kotlin analyzer. It is cheap and
    // CI cannot supply it from the artifact, so reuse must never drop it.
    dependsOn(":core:sonarJavaLibraries")

    if (!sonarReuseOutputs) {
        dependsOn(
            ":desktop-ui:test", ":desktop-ui:integrationTest", ":desktop-ui:jacocoTestReport",
            ":core:jvmTest", ":core:integrationTest", ":core:jacocoTestReport",
            ":dispatcher-agent:test", ":dispatcher-agent:integrationTest", ":dispatcher-agent:jacocoTestReport",
            "jacocoAggregatedReport",
        )
    }
}

// ===========================================
// Aggregated JaCoCo Report (cross-module)
// ===========================================

// Pin the aggregate's engine to the same version the modules pin: without this the
// report every module's xmlReportPaths consumes floats on Gradle's default, and a
// Gradle upgrade can silently swap the engine behind the cross-module coverage.
jacoco {
    toolVersion = jacocoToolVersion
}

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
        // XML is the only output anything consumes — Sonar through the modules'
        // xmlReportPaths, the sonar-inputs artifact, the workflow's verify step.
        // The HTML site (13 MB, about 1200 files per CI run) had no consumer, so
        // stop generating it; the per-module HTML reports stay for local browsing.
        xml.required.set(true)
        xml.outputLocation.set(file("build/reports/jacoco/aggregated/jacocoTestReport.xml"))
        html.required.set(false)
        csv.required.set(false)
    }
}
