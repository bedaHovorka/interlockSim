/*
 * core/build.gradle.kts
 *
 * Gradle build configuration for interlockSim :core module
 * Uses Kotlin Multiplatform (JVM primary runtime target, with linuxX64
 * also configured for native compilation/testing)
 * Extracts domain, context, objects, sim, util, exceptions packages
 *
 * ## commonMain vs JVM target
 *
 * The JVM target is currently the primary runtime target, and linuxX64
 * is also configured for native compilation/testing. `commonMain` is
 * still effectively JVM-constrained today. The Kotlin Multiplatform
 * plugin was chosen deliberately to keep the future path open:
 *
 *  1. Additional non-JVM targets (for example JS for a web demo) can
 *     be added by registering a new `kotlin { ... }` target;
 *     commonMain stays portable.
 *  2. The long-term simulation engine target is Kalasim
 *     (Kotlin-coroutines-based, Phase 2 in docs/jdisco-research.md),
 *     which benefits from commonMain staying free of JVM-only APIs.
 *  3. kDisco 0.4.0 ships multiplatform artifacts (jvm + linuxX64
 *     klibs) and this module consumes the multiplatform coordinate
 *     (`cz.hovorka.kdisco:kdisco-core`; see commonMain dependencies
 *     below). Remaining blockers for fully portable common code are
 *     test infrastructure (MockK/JUnit5 are JVM-only; commonTest is
 *     restricted to kotlin.test) and a Koin/xmlutil native audit on
 *     all intended targets.
 *
 * The `checkCoreCommonMainPurity` task (declared later in this file
 * under "CommonMain Purity Gate") enforces that commonMain stays free
 * of `java.*` / `javax.*` / `android.*` imports so the day a non-JVM
 * target is enabled, nothing in commonMain has to move.
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
val kotlinxIoVersion: String by project
val ktlintVersion: String by project

group = "cz.vutbr.fit"
version = "1.0"

// commonMain is KMP-clean: no java.*/javax.* imports, no JVM-only idioms.
// JVM-only code (xml/, context factories) lives in jvmMain.
// linuxX64 target added for native compilation verification.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    applyDefaultHierarchyTemplate()
    jvm {
        compilations.all {
            kotlinOptions {
                jvmTarget = "21"
                freeCompilerArgs += "-Xexpect-actual-classes"
            }
        }
        testRuns["test"].executionTask.configure {
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
    // kDisco 0.5.0 ships a linuxX64 klib; kotlinx-coroutines-core, koin-core, assertk all have native variants.
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
                // kotlinx-io: multiplatform file I/O — used by native Resources actual to read
                // resource files from disk (JVM uses classpath instead).
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:$kotlinxIoVersion")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("com.willowtreeapps.assertk:assertk:$assertkVersion")
                implementation("io.insert-koin:koin-test:$koinVersion")
                // Shared test fixtures and XML constants (CommonTestFixtures, NetworkResources, etc.)
                implementation(project(":core-test"))
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
// Native Resource Root Generation
// ===========================================
// Native targets have no classpath. The multiplatform Resources.read() actual
// for linuxX64 needs a list of directories to search on disk. This task
// generates a Kotlin file containing that list as a constant, and registers
// the generated directory as a Kotlin srcDir for nativeMain.
//
// KNOWN LIMITATION (Phase 1): the generated NATIVE_RESOURCE_ROOTS contains
// absolute paths into the local checkout (e.g. /home/.../core/src/commonMain/
// resources). This is sufficient for running tests and applications from the
// build tree, but a :fast-sim CLI binary copied to a different machine or
// path will fail with IllegalArgumentException at first Resources.read() call.
// Phase 2 will switch to binary-relative / klib-embedded resource lookup when
// kotlinx.io + KMP resources APIs mature. Until then: distribute :fast-sim
// alongside its resource roots, or invoke it from the build tree.

val nativeResourceRootDir =
    layout.buildDirectory.dir("generated/nativeResourceRoot/kotlin")

/** Escape a filesystem path for safe embedding as a Kotlin string literal. */
fun String.escapeKotlinStringLiteral(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

val generateNativeResourceRoot =
    tasks.register("generateNativeResourceRoot") {
        val coreMainResources = layout.projectDirectory.dir("src/commonMain/resources").asFile
        val coreTestResources = layout.projectDirectory.dir("src/commonTest/resources").asFile
        val coreTestProjectResources =
            rootProject.layout.projectDirectory
                .dir("core-test/src/commonMain/resources")
                .asFile
        val outDirProvider = nativeResourceRootDir
        // Paths (not contents) are the only inputs — the generated file only lists roots,
        // it does not embed resource content. Directory content changes are tracked
        // separately by tasks that consume those directories (native test, compile).
        inputs.property("coreMain", coreMainResources.absolutePath)
        inputs.property("coreTest", coreTestResources.absolutePath)
        inputs.property("coreTestProj", coreTestProjectResources.absolutePath)
        outputs.dir(outDirProvider)
        doLast {
            val file =
                outDirProvider
                    .get()
                    .asFile
                    .resolve("cz/vutbr/fit/interlockSim/util/NativeResourceRoot.kt")
            file.parentFile.mkdirs()
            val main = coreMainResources.absolutePath.escapeKotlinStringLiteral()
            val test = coreTestResources.absolutePath.escapeKotlinStringLiteral()
            val proj = coreTestProjectResources.absolutePath.escapeKotlinStringLiteral()
            file.writeText(
                buildString {
                    appendLine("// Generated by Gradle task generateNativeResourceRoot — do not edit.")
                    appendLine("package cz.vutbr.fit.interlockSim.util")
                    appendLine()
                    appendLine("internal val NATIVE_RESOURCE_ROOTS: List<String> =")
                    appendLine("\tlistOf(")
                    appendLine("\t\t\"$main\",")
                    appendLine("\t\t\"$test\",")
                    appendLine("\t\t\"$proj\"")
                    appendLine("\t)")
                },
            )
        }
    }

kotlin.sourceSets.named("nativeMain") {
    kotlin.srcDir(nativeResourceRootDir)
}

tasks.named("compileKotlinLinuxX64").configure { dependsOn(generateNativeResourceRoot) }

// ktlint's per-source-set check tasks consume the generated dir too; declare dependency explicitly
// so Gradle doesn't warn about an implicit producer/consumer relationship.
// Also exclude the generated file — the global filter{} block doesn't apply to per-source-set tasks.
tasks
    .matching {
        it.name == "runKtlintCheckOverNativeMainSourceSet" ||
            it.name == "runKtlintFormatOverNativeMainSourceSet"
    }.configureEach { dependsOn(generateNativeResourceRoot) }

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

    testClassesDirs =
        kotlin
            .jvm()
            .compilations["test"]
            .output.classesDirs
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
    mustRunAfter(tasks.named("integrationTest"))

    executionData.setFrom(
        fileTree(layout.buildDirectory).include("jacoco/*.exec"),
    )
    sourceDirectories.setFrom(
        files("src/commonMain/kotlin", "src/jvmMain/kotlin"),
    )
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("classes/kotlin/jvm/main")),
    )

    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jvmTest/jacocoTestReport.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/jvmTest/html"))
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
    version.set(ktlintVersion)
    verbose.set(true)
    outputToConsole.set(true)
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

// ===========================================
// checkKdisco dependency
// ===========================================

tasks.named("compileKotlinJvm") {
    dependsOn(rootProject.tasks.named("checkKdisco"))
}

// ===========================================
// CommonMain Purity Gate
// ===========================================
// Ensures commonMain stays free of JVM-only imports (java.*, javax.*, android.*)
// and JVM-only idioms (System.*). This is required for adding non-JVM KMP targets
// in the future.
//
// Detection strategy: deny-list of common known-bad patterns. Any match fails the build.
// This is simpler and less strict than a whitelist, but it is not comprehensive:
// it only catches the specific JVM-only patterns matched below.

val checkCoreCommonMainPurity by tasks.registering {
    group = "verification"
    description = "Verify :core commonMain contains no java.*, javax.*, android.* imports or System.* calls"

    val commonMainDir = file("src/commonMain/kotlin")
    inputs
        .dir(commonMainDir)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)

    doLast {
        if (!commonMainDir.exists()) {
            logger.info("commonMain directory {} does not exist, skipping purity check.", commonMainDir)
            return@doLast
        }

        // Catch import-level java.*/javax.*/android.* references
        val importRegex = Regex("^import\\s+(java|javax|android)\\..*")
        // Catch inline fully-qualified java.* references (e.g. java.util.TreeSet, java.lang.*)
        val inlineJavaRegex = Regex("(?<![\\w])java\\.[a-z]")
        // Catch inline fully-qualified android.* references (e.g. android.os.Bundle)
        val inlineAndroidRegex = Regex("(?<![\\w])android\\.[a-zA-Z]")
        // Catch System.* calls (java.lang.System is implicitly available on JVM only)
        val systemRegex = Regex("(?<![\\w.])System\\.[a-zA-Z]")
        val violations = mutableListOf<String>()

        commonMainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()
                // Skip comment lines
                if (trimmed.startsWith("//") ||
                    trimmed.startsWith("*") ||
                    trimmed.startsWith("/*")
                ) {
                    return@forEachIndexed
                }
                val hasViolation =
                    importRegex.containsMatchIn(line) ||
                        inlineJavaRegex.containsMatchIn(line) ||
                        inlineAndroidRegex.containsMatchIn(line) ||
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
                "ERROR: commonMain contains JVM-only code. Move java.*/javax.*/android.*/System.* usages to jvmMain.",
            )
        }

        println("commonMain purity check passed - no JVM-only code found.")
    }
}

tasks.named("check") {
    dependsOn(checkCoreCommonMainPurity)
}
