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

plugins {
    kotlin("jvm")
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

    // Test dependencies
    testImplementation(project(":core-test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitJupiterVersion")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:$assertkVersion")
    testImplementation("ch.qos.logback:logback-classic:$logbackVersion")
    // kDisco: :core uses implementation() so the kDisco API is not exported transitively;
    // add explicitly so test code that compiles against ShuntingLoop (a kDisco Process
    // subclass) can resolve kDisco supertype members.
    testImplementation("cz.hovorka.kdisco:kdisco-core-jvm:$kdiscoVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration-test", "heavy-test")
    }
    jvmArgs("-ea")

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Run integration tests (tagged with @Tag(\"integration-test\"))"

    useJUnitPlatform {
        includeTags("integration-test")
        excludeTags("heavy-test")
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

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.7")
}
