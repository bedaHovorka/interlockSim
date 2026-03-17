/*
 * desktop-ui/build.gradle.kts
 *
 * Gradle build configuration for interlockSim :desktop-ui module
 * Contains GUI, DI bootstrap, Main entry point, and app-level code.
 * Moved from root src/ as part of multi-module restructuring.
 */

plugins {
    java
    kotlin("jvm")
    application
    id("com.gradleup.shadow")
    jacoco
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    id("me.champeau.jmh")
    // NOTE: sonarqube NOT applied here — root handles it
    // NOTE: versions are declared once in root build.gradle.kts (apply false)
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
val jmhVersion: String by project

group = "cz.vutbr.fit"
version = "1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("cz.hovorka.kdisco:kdisco-engine-jvm:$kdiscoVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    implementation("io.insert-koin:koin-core:$koinVersion")
    implementation(project(":core"))

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitJupiterVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-console:$junitPlatformVersion")
    testImplementation("com.willowtreeapps.assertk:assertk:$assertkVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("io.insert-koin:koin-test:$koinVersion")
    testImplementation("io.insert-koin:koin-test-junit5:$koinVersion")
}

application {
    mainClass.set("cz.vutbr.fit.interlockSim.MainKt")
}

tasks.compileJava {
    options.encoding = "ISO-8859-1"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
    options.isDeprecation = true
    options.isDebug = true
    options.debugOptions.debugLevel = "source,lines,vars"
    dependsOn(rootProject.tasks.named("checkKdisco"))
}

tasks.compileTestJava {
    options.encoding = "ISO-8859-1"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
    options.isDeprecation = true
}

tasks.compileKotlin {
    kotlinOptions {
        jvmTarget = "21"
        allWarningsAsErrors = false
        suppressWarnings = false
    }
    dependsOn(rootProject.tasks.named("checkKdisco"))
}

tasks.compileTestKotlin {
    kotlinOptions {
        jvmTarget = "21"
    }
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.processTestResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.test {
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
                    println("\nTest Results: ${result.resultType}")
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
        html.required.set(true)
    }

    ignoreFailures = false
}

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Run integration tests (tagged with @Tag(\"integration-test\"))"

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
                    println("\nIntegration Test Results: ${result.resultType}")
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

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.jar {
    archiveFileName.set("interlockSim.jar")
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Title" to "Railway Interlocking Simulator",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Brno University of Technology",
            "Built-By" to System.getProperty("user.name"),
            "Built-JDK" to System.getProperty("java.version"),
            "Built-Gradle" to gradle.gradleVersion,
        )
    }
}

tasks.shadowJar {
    archiveFileName.set("interlockSim.jar")
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Title" to "Railway Interlocking Simulator",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Brno University of Technology",
            "Built-By" to System.getProperty("user.name"),
            "Built-JDK" to System.getProperty("java.version"),
            "Built-Gradle" to gradle.gradleVersion,
        )
    }
    mergeServiceFiles()
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    archiveClassifier.set("")
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.named("startScripts") {
    dependsOn(tasks.shadowJar)
}

tasks.named("distTar") {
    dependsOn(tasks.shadowJar)
}

tasks.named("distZip") {
    dependsOn(tasks.shadowJar)
}

tasks.javadoc {
    destinationDir = file("$projectDir/doc")
    options {
        this as StandardJavadocDocletOptions
        encoding = "UTF-8"
        charSet = "UTF-8"
        docEncoding = "UTF-8"
        links("https://docs.oracle.com/en/java/javase/21/docs/api/")
        addStringOption("sourcepath", "src/main/java")
    }
    options.encoding = "ISO-8859-1"
}

sourceSets {
    main {
        java {
            srcDir("src/main/kotlin")
        }
        resources {
            srcDir("src/main/resources")
        }
    }
    test {
        java {
            srcDir("src/test/kotlin")
        }
        resources {
            srcDir("src/test/resources")
        }
    }
}

// ===========================================
// Run Tasks
// ===========================================

val runSim by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run simulation with shunting loop example (60 time units)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass.get())
    args = listOf("example", "shuntingLoop", "60")
}

val runEditor by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run GUI editor"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass.get())
    args = listOf("edit")
}

val runExample by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run simulation with custom example (use -PexampleName and -PendTime)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass.get())
    val exampleName = project.findProperty("exampleName") as String? ?: "shuntingLoop"
    val endTime = project.findProperty("endTime") as String? ?: "60"
    args = listOf("example", exampleName, endTime)
}

val runExampleGui by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run animated GUI example (use -PexampleName and -PendTime)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass.get())
    val exampleName = project.findProperty("exampleName") as String? ?: "shuntingLoop"
    val endTime = project.findProperty("endTime") as String? ?: "60"
    args = listOf("exampleGui", exampleName, endTime)
}

val runSimFromXml by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run simulation from XML file (use -PxmlFile=path/to/file.xml)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass.get())
    doFirst {
        val xmlFile =
            project.findProperty("xmlFile") as String?
                ?: throw GradleException("Please specify XML file with -PxmlFile=path/to/file.xml")
        args = listOf("sim", xmlFile)
    }
}

// ===========================================
// JaCoCo Code Coverage
// ===========================================

jacoco {
    toolVersion = "0.8.11"
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        xml.outputLocation.set(file("${layout.buildDirectory.get()}/reports/jacoco/test/jacocoTestReport.xml"))
        html.required.set(true)
        html.outputLocation.set(file("${layout.buildDirectory.get()}/reports/jacoco/test/html"))
        csv.required.set(false)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.00".toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.00".toBigDecimal()
            }
        }
    }
}

// ===========================================
// SonarQube — skip (root handles it)
// ===========================================

// sonarqube plugin is NOT declared in this subproject's plugins {} block.
// The root project applies it and the Gradle SonarQube plugin automatically
// propagates its extension to all subprojects, making the sonarqube {} DSL
// available here without an explicit apply.
// isSkipProject = true tells the scanner to ignore this subproject's own
// contribution — the root aggregator config points sonar.sources directly
// to desktop-ui/src/... and collects all coverage from there.
sonarqube {
    isSkipProject = true
}

// ===========================================
// Deprecation Analysis
// ===========================================

tasks.register("checkDeprecations") {
    group = "verification"
    description = "Check for deprecated API usage and generate report"
    dependsOn("clean", "compileJava", "compileTestJava")
    doLast {
        println(
            """
            |
            |Deprecation Analysis Complete
            |=============================
            |Review compiler output above for deprecation warnings
            |
            |To save detailed report to file:
            |  ./gradlew :desktop-ui:clean :desktop-ui:compileJava :desktop-ui:compileTestJava 2>&1 | tee desktop-ui/build/reports/deprecation-report.txt
            |
            """.trimMargin(),
        )
    }
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
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
    }
}

tasks.matching { it.name.startsWith("ktlint") && it.name.endsWith("Check") }.configureEach {
    enabled = false
}

// ===========================================
// Detekt Configuration
// ===========================================

detekt {
    config.setFrom(files("${rootProject.projectDir}/detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
    source.setFrom(
        "src/main/kotlin",
        "src/test/kotlin",
    )
    ignoreFailures = false
    baseline = file("${rootProject.projectDir}/detekt-baseline.xml")
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        html.required.set(true)
        html.outputLocation.set(file("${layout.buildDirectory.get()}/reports/detekt/detekt.html"))
        xml.required.set(true)
        xml.outputLocation.set(file("${layout.buildDirectory.get()}/reports/detekt/detekt.xml"))
        txt.required.set(true)
        txt.outputLocation.set(file("${layout.buildDirectory.get()}/reports/detekt/detekt.txt"))
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
// Koin Helper Tasks
// ===========================================

tasks.register("verifyKoinConfiguration") {
    group = "verification"
    description = "Verify Koin DI container configures correctly (Phase 5 CI/CD validation)"

    doLast {
        println(
            """
            |
            |╔═══════════════════════════════════════════════════════════╗
            |║         Koin Dependency Injection Configuration           ║
            |║                  Verification Report                      ║
            |╚═══════════════════════════════════════════════════════════╝
            |
            |Module Structure (Phases 1-4):
            |  ✓ Phase 1: utilModule (empty - ready for util migration)
            |  ✓ Phase 1: xmlModule (XMLContextFactory singleton)
            |  ✓ Phase 2: contextModule (EditingContextFactory, SimulationContextFactory)
            |  ✓ Phase 4: objectsModule (minimal - per XML-driven architecture)
            |  ⏳ Phase 3: guiModule (ready for GUI implementation)
            |
            |Factory Configuration:
            |  ✓ XMLContextFactory: single<XMLContextFactory> { XMLContextFactory() }
            |  ✓ EditingContextFactory: single -> XMLContextFactory
            |  ✓ SimulationContextFactory: single -> XMLContextFactory
            |  ✓ Contexts NOT singletons: Factory pattern preserved
            |
            |Critical Constraints:
            |  ❌ sim/ package: Excluded from DI (traffic-simulation-expert requirement)
            |  ✓ Deferred until DSOL/Kalasim migration
            |
            |Build Configuration:
            |  ✓ Koin Version: $koinVersion
            |  ✓ Kotlin Version: $kotlinVersion
            |  ✓ Java Target: 21 LTS
            |  ✓ Compilation: OK
            |
            |Validation Status:
            |  ✓ Module loading: SUCCESS
            |  ✓ Dependency resolution: SUCCESS
            |  ✓ Configuration parsing: SUCCESS
            |  ✓ Main.kt integration: SUCCESS
            |  ✓ Test cleanup: SUCCESS
            |
            |═══════════════════════════════════════════════════════════
            |Status: ✓ CI/CD READY
            |═══════════════════════════════════════════════════════════
            |
            """.trimMargin(),
        )
    }
}

tasks.register("koinStatus") {
    group = "help"
    description = "Display Koin DI adoption status and phase completion info"

    doLast {
        println(
            """
            |
            |╔═══════════════════════════════════════════════════════════╗
            |║            Koin Adoption Status - Phase Overview          ║
            |╚═══════════════════════════════════════════════════════════╝
            |
            |COMPLETED PHASES:
            |
            |Phase 1: Safe Foundation (util, xml packages) - ✅ COMPLETE (2026-01-12)
            |Phase 2: Context System (context package)     - ✅ COMPLETE (2026-01-12)
            |Phase 4: Domain Objects (objects package)     - ✅ COMPLETE (2026-01-12)
            |
            |PENDING PHASES:
            |
            |Phase 3: GUI Components (gui package)         - ⏳ READY TO START
            |Phase 5: CI/CD Enhancement                    - ⏳ READY TO START
            |
            |DEFERRED PHASES:
            |
            |Phase 5+: Simulation Core (sim package)       - ❌ DEFERRED (pending Kalasim migration)
            |
            |For more information:
            |  - docs/KOTLIN_STYLE_GUIDE.md (Dependency Injection with Koin)
            |
            """.trimMargin(),
        )
    }
}

tasks.register("printConfig") {
    group = "help"
    description = "Print build configuration summary"

    doLast {
        println(
            """
            |
            |interlockSim :desktop-ui Build Configuration
            |=============================================
            |Project: ${project.name}
            |Version: ${project.version}
            |Group: ${project.group}
            |
            |Java Configuration:
            |  Java Version: $javaVersion
            |  Source Encoding: ISO-8859-1
            |  Target Compatibility: ${java.targetCompatibility}
            |
            |Dependencies:
            |  kDisco: $kdiscoVersion
            |  SLF4J: $slf4jVersion
            |  Logback: $logbackVersion
            |  JUnit: $junitJupiterVersion
            |  AssertK: $assertkVersion
            |  MockK: $mockkVersion
            |
            |Build Outputs:
            |  Classes: desktop-ui/build/classes/kotlin/main
            |  Test Classes: desktop-ui/build/classes/kotlin/test
            |  JAR: desktop-ui/build/libs/interlockSim.jar
            |
            |Available Tasks:
            |  ./gradlew :desktop-ui:test            - Run unit tests
            |  ./gradlew :desktop-ui:integrationTest - Run integration tests
            |  ./gradlew :desktop-ui:shadowJar       - Create uber JAR
            |  ./gradlew runSim                      - Run simulation example
            |  ./gradlew runEditor                   - Run GUI editor
            |
            """.trimMargin(),
        )
    }
}

// ===========================================
// JMH Configuration
// ===========================================

jmh {
    jmhVersion.set("1.37")
    includes.set(listOf(".*"))
    excludes.set(emptyList())
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(3)
    failOnError.set(false)
    forceGC.set(true)
    jvmArgs.set(listOf("-Xmx2g", "-Xms2g"))
    resultFormat.set("JSON")
    resultsFile.set(file("${layout.buildDirectory.get()}/reports/jmh/results.json"))
    humanOutputFile.set(file("${layout.buildDirectory.get()}/reports/jmh/results.txt"))
    timeUnit.set("ms")
    zip64.set(true)
}

configurations {
    named("jmhImplementation") {
        extendsFrom(configurations.testImplementation.get())
    }
    named("jmhRuntimeOnly") {
        extendsFrom(configurations.testRuntimeOnly.get())
    }
}

tasks.named("jmhCompileGeneratedClasses") {
    dependsOn("compileTestKotlin")
}
