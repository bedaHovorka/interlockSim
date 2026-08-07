/*
 * dispatcher-agent/build.gradle.kts
 *
 * :dispatcher-agent — Goal 10 dispatcher module
 *
 * This module houses the Dispatcher seam and its implementations:
 *
 *   - The deterministic [RuleBasedDispatcher] baseline (SP0.1, Issue #540) lives in
 *     :core for practical dependency-graph reasons: :core must not depend on
 *     :dispatcher-agent (circular), and :fast-sim must not pull in LLM framework
 *     transitive dependencies.
 *
 *   - [DispatchDecision][cz.vutbr.fit.interlockSim.sim.DispatchDecision] also
 *     lives in :core (moved there by Issue #729 / SP0.7) for the same reason.
 *
 *   - This module is the designated home for:
 *       • SP1 (Issues #546–#551): Koog + Koin agent runtime, Ollama executor
 *       • SP2b (Issues #556–#566, #695): LLM dispatcher richness (e.g. HoldTrain,
 *         switch/signal/speed decisions), Koin module, DispatcherControlPanel wiring
 *       • SP3 (Issues #570–#578): Operating-language DSL and pluggable planners
 *
 *   - It intentionally has NO Koog/Ollama dependencies at SP0.1 stage.  Those are
 *     added in SP1 (#546, #547) once the module structure is validated end-to-end.
 *
 * Module position:
 *   :core  ←  :dispatcher-agent  (depends on :core; never the other way round)
 *   :dispatcher-agent  ←  :desktop-ui  (GUI wiring, Stage B)
 *   :fast-sim does NOT depend on :dispatcher-agent (native binary, no LLM deps)
 *
 * @since Issue #540 (SP0.1 — Goal 10)
 */

import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
    jacoco
}

// Load versions from root gradle.properties
val kdiscoVersion: String by project
val kotlinLoggingVersion: String by project
val junitJupiterVersion: String by project
val assertkVersion: String by project
val koinVersion: String by project
val logbackVersion: String by project
val slf4jVersion: String by project
val mockkVersion: String by project
val coroutinesVersion: String by project
val koogVersion: String by project
val serializationVersion: String by project

group = "cz.vutbr.fit"
version = "1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // :core provides Dispatcher / DispatcherTickContext interfaces, RuleBasedDispatcher,
    // and all domain types (Train, DynamicTrackBlock, PathReservationService, …).
    implementation(project(":core"))

    // Logging
    implementation("io.github.oshai:kotlin-logging:$kotlinLoggingVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")

    // Koin: used for DI of dispatcher implementations (SP1 will expand this)
    implementation("io.insert-koin:koin-core:$koinVersion")

    // Koog: LLM agent framework for SP1 (Goal 10 dispatcher agent runtime, Issue #547)
    implementation("ai.koog:koog-agents:$koogVersion")

    // kotlinx.serialization: SP3.2 vocab and SP3.3 proto packages use @Serializable
    // The core and json libs are also available transitively through koog-agents,
    // but listed explicitly here for compile-time clarity and stable versioning.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    // kotlinx-coroutines-core: SP2b.9 (Issue #566) KoogAgentPlanAdapter uses withTimeout
    // and CancellationException. Available transitively via koog-agents, but listed
    // explicitly here for compile-time clarity and stable versioning.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // Test dependencies
    testImplementation(project(":core-test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitJupiterVersion")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:$assertkVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("ch.qos.logback:logback-classic:$logbackVersion")
    // MockWebServer (SP2b.9, Issue #815): network-free HTTP mock for OllamaModelPrewarmer's
    // live POST path/method/body assertion. Test-only; production code uses the JDK HttpClient.
    // Pinned to the OkHttp 5.x line: a transitive dep (aws smithy kotlin via koog-agents)
    // forces okhttp to 5.3.2, and OkHttp 5.x removed okhttp3.internal.Util that
    // mockwebserver:4.x is built against — a 4.x mockwebserver throws NoClassDefFoundError.
    testImplementation("com.squareup.okhttp3:mockwebserver:5.0.0")
    // kDisco: :core uses implementation() so the kDisco API is not exported transitively;
    // add explicitly so test code that compiles against ShuntingLoop (a kDisco Process
    // subclass) can resolve kDisco supertype members.
    testImplementation("cz.ksimulantenbande.kdisco:kdisco-core-jvm:$kdiscoVersion")
    // kotlinx-coroutines-core: :core uses implementation() so it is not exported
    // transitively; add explicitly for test code that uses runBlocking (e.g. AgentLoopDriverTest).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
}

tasks.test {
    useJUnitPlatform {
        // "ollama-test" (SP1.3, Issue #548): proof-of-connection tests against a real local
        // Ollama instance. Never run as part of the plain unit-test task; only conditionally
        // included by the `integrationTest` task below, based on a live reachability probe.
        excludeTags("integration-test", "heavy-test", "ollama-test")
    }
    jvmArgs("-ea")

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/**
 * Whether Ollama answers on its default port. Checked fresh every time [integrationTest]
 * actually executes (see the `doFirst` block below) rather than once at configuration time,
 * since local Ollama availability can change between builds.
 */
fun isOllamaReachable(): Boolean =
    try {
        Socket().use {
            it.connect(InetSocketAddress("localhost", 11434), 300)
        }
        true
    } catch (e: IOException) {
        false
    }

// GitHub Actions sets both CI=true and GITHUB_ACTIONS=true; checking either covers CI running
// outside GitHub Actions too, without hardcoding a single CI vendor's env var.
val isCi = System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Run integration tests (tagged with @Tag(\"integration-test\"))"

    useJUnitPlatform {
        includeTags("integration-test")
        excludeTags("heavy-test", "ollama-test")
    }

    jvmArgs("-ea")
    maxParallelForks = 1

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }

    reports {
        junitXml.required.set(true)
        junitXml.outputLocation.set(file("${layout.buildDirectory.get()}/test-results/integrationTest"))
        html.required.set(true)
        html.outputLocation.set(file("${layout.buildDirectory.get()}/reports/tests/integrationTest"))
    }

    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    // SP1.3 (Issue #548): decide whether "ollama-test"-tagged tests are part of this run based
    // on a live reachability probe, not a manual flag. CI is intentionally Ollama-free (real
    // LLM-output benchmarking is SP3.5/SP2b.9's job, not SP1.3's) so an unreachable Ollama there
    // just warns and excludes the tag. Locally, an unreachable Ollama fails the build outright —
    // integrationTest must not silently skip its proof-of-connection coverage on a dev machine.
    doFirst {
        val ollamaAvailable = isOllamaReachable()
        if (!ollamaAvailable) {
            if (isCi) {
                logger.warn(
                    "Ollama not reachable at localhost:11434 - excluding ollama-test-tagged tests (expected in CI)",
                )
            } else {
                throw org.gradle.api.GradleException(
                    "Ollama not reachable at localhost:11434. Local integrationTest requires Ollama - " +
                        "start it natively ('ollama serve') or via 'docker compose up -d ollama', then retry.",
                )
            }
        }
        val options = options as JUnitPlatformOptions
        options.setIncludeTags(
            if (ollamaAvailable) setOf("integration-test", "ollama-test") else setOf("integration-test"),
        )
        options.setExcludeTags(setOf("heavy-test"))
    }
}

val heavyTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Run heavy tests (tagged with @Tag(\"heavy-test\")). " +
        "Run after changes to simulation-adjacent concurrency/pacing primitives " +
        "(e.g. AgentLoopDriver / SnapshotSignal, Issue #746) to detect deadlocks, " +
        "race conditions, or resource leaks."

    useJUnitPlatform {
        includeTags("heavy-test")
    }

    systemProperty("junit.jupiter.params.repeat.maxCount", properties["heavyTestRepeatMaxCount"])

    jvmArgs("-ea")
    maxParallelForks = 1

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        showStandardStreams = false
    }

    reports {
        junitXml.required.set(true)
        junitXml.outputLocation.set(file("${layout.buildDirectory.get()}/test-results/heavyTest"))
        html.required.set(true)
        html.outputLocation.set(file("${layout.buildDirectory.get()}/reports/tests/heavyTest"))
    }

    ignoreFailures = false

    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}

// ===========================================
// dispatcherReliabilityReport task (SP2c.23, Issue #846)
// ===========================================
//
// Aggregates per-run JSON snapshots from build/reports/dispatcher-runs/ into a Markdown
// report at build/reports/dispatcher-runs/report.md.
//
// Usage: ./gradlew dispatcherReliabilityReport
//
// No simulation is re-run; the task reads existing JSON files only.

val dispatcherReliabilityReport by tasks.registering(JavaExec::class) {
    group = "reporting"
    description = "Aggregate dispatcher run JSONs into build/reports/dispatcher-runs/report.md (no simulation re-run)"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherReliabilityReportKt")

    // Issue #847 round 4: read the runs where headless runs actually write them.
    // DefaultRunSnapshotStore.DEFAULT_ROOT is a RELATIVE path ("build/reports/dispatcher-runs"),
    // so it resolves against the working directory. `java -jar interlockSim.jar example ...` is
    // launched from the repository root and writes there, while a JavaExec task defaults its
    // working directory to the *module* directory and would read
    // dispatcher-agent/build/reports/dispatcher-runs -- a different, empty directory. The task then
    // succeeds and renders an all-zero report, which is indistinguishable from "no runs were
    // recorded" and is exactly the silent-empty-measurement failure #847 cannot afford.
    workingDir = rootProject.projectDir

    dependsOn(tasks.named("classes"))
}

// ===========================================
// checkKdisco dependency
// ===========================================

tasks.named("compileKotlin") {
    dependsOn(rootProject.tasks.named("checkKdisco"))
}

// ===========================================
// Detekt configuration
// ===========================================

detekt {
    config.setFrom(files("${rootProject.projectDir}/detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
    source.setFrom("src/main/kotlin")
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

// ===========================================
// JaCoCo Report Configuration
// ===========================================

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    mustRunAfter(tasks.named("integrationTest"))

    executionData.setFrom(
        fileTree(layout.buildDirectory).include("jacoco/*.exec"),
    )
    sourceDirectories.setFrom(
        files("src/main/kotlin"),
    )
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("classes/kotlin/main")),
    )

    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/test/html"))
        csv.required.set(false)
    }
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.7")
}

// ===========================================
// SonarQube per-module configuration
// ===========================================
//
// The SonarQube Gradle plugin v6+ auto-detects source sets for every Kotlin JVM
// subproject. If the root build.gradle.kts ALSO lists this module's source paths in
// sonar.sources / sonar.tests, the same files get indexed twice, causing:
//   "File ... can't be indexed twice. Please check that inclusion/exclusion patterns
//    produce disjoint sets for main and test files"  (Issue #762)
//
// Fix: declare sources/tests explicitly here so the scanner uses ONLY this configuration
// for the :dispatcher-agent module. The root sonar{} block intentionally omits these paths.

sonar {
    properties {
        property("sonar.sources", "src/main/kotlin")
        property("sonar.tests", "src/test/kotlin")
        property("sonar.java.binaries", "build/classes/kotlin/main")
        property("sonar.java.test.binaries", "build/classes/kotlin/test")
        property("sonar.sourceEncoding", "UTF-8")
    }
}
