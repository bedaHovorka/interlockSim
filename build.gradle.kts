/*
 * build.gradle.kts
 *
 * Gradle build configuration for interlockSim project
 * Migrated from Apache Ant + Ivy build system in 2026
 *
 * This file preserves all functionality from the original build.xml:
 * - Java 11 compilation with ISO-8859-1 encoding
 * - JUnit 5 test execution with assertions enabled
 * - Uber JAR creation with Shadow plugin
 * - Custom run tasks for simulation and editor modes
 * - JavaDoc generation
 */

plugins {
    // Core Java support
    java

    // Application plugin for executable JARs and run tasks
    application

    // Shadow plugin for creating uber JAR (fat JAR with all dependencies)
    id("com.github.johnrengelman.shadow") version "8.1.1"

    // SonarQube plugin for code quality analysis
    // Version 6.2.0 - Migrates from deprecated Gradle APIs
    id("org.sonarqube") version "6.2.0.5505"

    // JaCoCo plugin for code coverage (required for SonarQube)
    jacoco
}

// Load versions from gradle.properties
val jdiscoVersion: String by project
val slf4jVersion: String by project
val logbackVersion: String by project
val junitPlatformVersion: String by project
val junitJupiterVersion: String by project
val assertjVersion: String by project
val mockitoVersion: String by project
val javaVersion: String by project

// Project group and version
group = "cz.vutbr.fit"
version = "1.0"

// Configure Java compilation (compile to Java 11 bytecode using any Java 11+)
// Note: We don't specify a toolchain to allow using any available Java version
java {
    // Ensure source and target compatibility with Java 11
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// Configure repositories
// IMPORTANT: mavenLocal() must come first to resolve jDisco
repositories {
    mavenLocal()      // For jDisco (must be pre-installed via: cd jdisco && mvn install)
    mavenCentral()    // For all other dependencies
}

// Dependencies (matching Ivy configuration exactly)
dependencies {
    // Compile dependencies (from Ivy compile configuration)
    implementation("dk.ruc.keld:jdisco:$jdiscoVersion")              // Discrete event simulation library
    implementation("org.slf4j:slf4j-api:$slf4jVersion")               // Logging facade
    implementation("ch.qos.logback:logback-classic:$logbackVersion")  // SLF4J implementation (includes logback-core)

    // Test dependencies (from Ivy test configuration)
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")         // JUnit 5 API
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")         // JUnit 5 engine
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")     // JUnit platform launcher
    testRuntimeOnly("org.junit.platform:junit-platform-console:$junitPlatformVersion")      // JUnit platform console
    testImplementation("org.assertj:assertj-core:$assertjVersion")                          // Fluent assertions
    testImplementation("org.mockito:mockito-core:$mockitoVersion")                          // Mocking framework
    testImplementation("org.mockito:mockito-junit-jupiter:$mockitoVersion")                 // Mockito-JUnit integration
}

// Configure application main class
application {
    mainClass.set("cz.vutbr.fit.interlockSim.Main")
}

// Enable assertions for all run tasks (including default 'run' task)
tasks.withType<JavaExec> {
    jvmArgs("-ea")
}

// Configure compilation tasks
tasks.compileJava {
    // CRITICAL: Use ISO-8859-1 encoding (legacy requirement from Ant build)
    options.encoding = "ISO-8859-1"

    // Enable all warnings including deprecation
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
    options.isDeprecation = true

    // Include debug information (matching Ant configuration)
    options.isDebug = true
    options.debugOptions.debugLevel = "source,lines,vars"
}

tasks.compileTestJava {
    // Match encoding for test sources
    options.encoding = "ISO-8859-1"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
    options.isDeprecation = true
}

// Configure resource processing (handle duplicate files)
tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.processTestResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

// Configure test execution
tasks.test {
    // Use JUnit Platform (JUnit 5)
    useJUnitPlatform {
        // Exclude integration tests from regular test runs
        excludeTags("integration-test")
    }

    // Enable assertions (matching Ant's -ea flag)
    jvmArgs("-ea")

    // PARALLEL EXECUTION ENABLED (per user preference)
    // Tests run concurrently for faster execution (~30-60 sec vs 1-2 min sequential)
    // If tests fail due to shared state, set maxParallelForks = 1
    maxParallelForks = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    // Test output configuration (matching Ant format)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true

        // Show standard output and error (matching Ant behavior)
        showStandardStreams = false

        // Show test summary after execution
        afterSuite(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
            if (desc.parent == null) {
                println("\nTest Results: ${result.resultType}")
                println("  Tests run: ${result.testCount}")
                println("  Passed: ${result.successfulTestCount}")
                println("  Failed: ${result.failedTestCount}")
                println("  Skipped: ${result.skippedTestCount}")
            }
        }))
    }

    // Generate XML reports for CI/CD (matching Ant's test output)
    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }

    // Fail fast on test failures (matching Ant's haltonfailure="yes")
    ignoreFailures = false
}

/**
 * Task: integrationTest
 * Run only integration tests (tests tagged with @Tag("integration-test"))
 * This allows separation of fast unit tests from slower integration tests
 */
val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Run integration tests (tagged with @Tag(\"integration-test\"))"

    // Use JUnit Platform with integration test tag filter
    useJUnitPlatform {
        includeTags("integration-test")
    }

    // Enable assertions
    jvmArgs("-ea")

    // Integration tests may be slower, use serial execution by default
    maxParallelForks = 1

    // Test output configuration
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        showStandardStreams = false

        afterSuite(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
            if (desc.parent == null) {
                println("\nIntegration Test Results: ${result.resultType}")
                println("  Tests run: ${result.testCount}")
                println("  Passed: ${result.successfulTestCount}")
                println("  Failed: ${result.failedTestCount}")
                println("  Skipped: ${result.skippedTestCount}")
            }
        }))
    }

    // Generate separate reports for integration tests
    reports {
        junitXml.required.set(true)
        junitXml.outputLocation.set(file("${layout.buildDirectory.get()}/test-results/integrationTest"))
        html.required.set(true)
        html.outputLocation.set(file("${layout.buildDirectory.get()}/reports/tests/integrationTest"))
    }

    ignoreFailures = false

    // Set different output directory to avoid conflicts with unit tests
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}

// Configure JAR task
tasks.jar {
    // Set fixed JAR name (no version suffix, per user preference)
    archiveFileName.set("interlockSim.jar")

    // Configure manifest
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Title" to "Railway Interlocking Simulator",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Brno University of Technology",
            "Built-By" to System.getProperty("user.name"),
            "Built-JDK" to System.getProperty("java.version"),
            "Built-Gradle" to gradle.gradleVersion
        )
    }
}

// Configure Shadow JAR (uber JAR with all dependencies)
tasks.shadowJar {
    // Set fixed JAR name (matching regular JAR)
    archiveFileName.set("interlockSim.jar")

    // Include same manifest attributes as regular JAR
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Title" to "Railway Interlocking Simulator",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Brno University of Technology",
            "Built-By" to System.getProperty("user.name"),
            "Built-JDK" to System.getProperty("java.version"),
            "Built-Gradle" to gradle.gradleVersion
        )
    }

    // Merge service files (critical for SLF4J and other service loaders)
    mergeServiceFiles()

    // Exclude signature files from dependency JARs (matching Ant's pack task)
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")

    // Set classifier to empty (no "-all" suffix)
    archiveClassifier.set("")
}

// Disable regular JAR task (we only want shadow JAR)
tasks.jar {
    enabled = false
}

// Make build task depend on shadowJar
tasks.build {
    dependsOn(tasks.shadowJar)
}

// Make shadowJar replace the regular jar in distributions
tasks.named("startScripts") {
    dependsOn(tasks.shadowJar)
}

tasks.named("distTar") {
    dependsOn(tasks.shadowJar)
}

tasks.named("distZip") {
    dependsOn(tasks.shadowJar)
}

// Configure JavaDoc generation
tasks.javadoc {
    // Output to doc/ directory (matching Ant configuration)
    destinationDir = file("$projectDir/doc")

    // Configure options
    options {
        this as StandardJavadocDocletOptions
        encoding = "UTF-8"  // Use UTF-8 for JavaDoc output
        charSet = "UTF-8"
        docEncoding = "UTF-8"
        links("https://docs.oracle.com/en/java/javase/11/docs/api/")

        // Include all packages
        addStringOption("sourcepath", "src/main/java")
    }

    // Set source encoding to ISO-8859-1 (matching source files)
    options.encoding = "ISO-8859-1"
}

// ===========================================
// Custom Tasks (matching Ant targets)
// ===========================================

/**
 * Task: checkJdisco
 * Verifies that jDisco library is available in Maven local repository
 * This matches Ant's check-jdisco target
 */
val checkJdisco by tasks.registering {
    group = "verification"
    description = "Verify jDisco library is installed in Maven local repository"

    doLast {
        val jdiscoJar = file("${System.getProperty("user.home")}/.m2/repository/dk/ruc/keld/jdisco/$jdiscoVersion/jdisco-$jdiscoVersion.jar")

        if (!jdiscoJar.exists()) {
            throw GradleException("""
                |
                |jDisco library not found at: ${jdiscoJar.absolutePath}
                |
                |Please build and install jDisco first:
                |  cd jdisco && mvn install
                |
                |Or use Docker:
                |  cd jdisco && docker compose run jdisco mvn install
                |
            """.trimMargin())
        }

        println("✓ jDisco $jdiscoVersion found at: ${jdiscoJar.absolutePath}")
    }
}

// Make compileJava depend on checkJdisco
tasks.compileJava {
    dependsOn(checkJdisco)
}

/**
 * Task: runSim
 * Run simulation with pre-configured shunting loop example
 * Matches Ant's 'start' target
 */
val runSim by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run simulation with shunting loop example (60 time units)"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass.get())
    args = listOf("example", "shuntingLoop", "60")

    // Enable assertions (matching Ant)
    jvmArgs("-ea")
}

/**
 * Task: runEditor
 * Run GUI editor
 * Matches Ant's 'run' target
 */
val runEditor by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run GUI editor"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass.get())
    args = listOf("edit")

    // Enable assertions
    jvmArgs("-ea")
}

/**
 * Task: runExample
 * Run simulation with custom example
 * Usage: ./gradlew runExample -PexampleName=shuntingLoop -PendTime=300
 */
val runExample by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run simulation with custom example (use -PexampleName and -PendTime)"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass.get())

    // Get parameters from project properties
    val exampleName = project.findProperty("exampleName") as String? ?: "shuntingLoop"
    val endTime = project.findProperty("endTime") as String? ?: "60"

    args = listOf("example", exampleName, endTime)
    jvmArgs("-ea")
}

/**
 * Task: runSimFromXml
 * Run simulation from XML file
 * Usage: ./gradlew runSimFromXml -PxmlFile=path/to/file.xml
 */
val runSimFromXml by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run simulation from XML file (use -PxmlFile=path/to/file.xml)"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass.get())

    // Enable assertions
    jvmArgs("-ea")

    // Validate and set XML file path at execution time (not configuration time)
    doFirst {
        val xmlFile = project.findProperty("xmlFile") as String?
            ?: throw GradleException("Please specify XML file with -PxmlFile=path/to/file.xml")
        args = listOf("sim", xmlFile)
    }
}

// ===========================================
// Additional Configuration
// ===========================================

// Configure source sets (Gradle default, matches Ant structure)
sourceSets {
    main {
        java {
            srcDir("src/main/java")
        }
        resources {
            srcDir("src/main/resources")
        }
    }
    test {
        java {
            srcDir("src/test/java")
        }
        resources {
            srcDir("src/test/resources")
        }
    }
}

// Print configuration summary
tasks.register("printConfig") {
    group = "help"
    description = "Print build configuration summary"

    doLast {
        println("""
            |
            |interlockSim Build Configuration
            |================================
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
            |  jDisco: $jdiscoVersion
            |  SLF4J: $slf4jVersion
            |  Logback: $logbackVersion
            |  JUnit: $junitJupiterVersion
            |  AssertJ: $assertjVersion
            |  Mockito: $mockitoVersion
            |
            |Build Outputs:
            |  Classes: build/classes/java/main
            |  Test Classes: build/classes/java/test
            |  JAR: build/libs/interlockSim.jar
            |  JavaDoc: doc/
            |
            |Available Tasks:
            |  ./gradlew clean           - Clean build outputs
            |  ./gradlew build           - Build and test project
            |  ./gradlew test            - Run all tests
            |  ./gradlew shadowJar       - Create uber JAR
            |  ./gradlew runSim          - Run simulation example
            |  ./gradlew runEditor       - Run GUI editor
            |  ./gradlew runExample      - Run custom example
            |  ./gradlew javadoc         - Generate JavaDoc
            |  ./gradlew dependencies    - Show dependency tree
            |
        """.trimMargin())
    }
}

// ===========================================
// JaCoCo Code Coverage Configuration
// ===========================================

/**
 * JaCoCo configuration for code coverage analysis
 * Required for SonarQube code coverage reporting
 */
jacoco {
    toolVersion = "0.8.11"  // Latest stable version
}

// Configure test task to generate JaCoCo coverage data
tasks.test {
    // Generate coverage data during test execution
    finalizedBy(tasks.jacocoTestReport)
}

// Configure JaCoCo test report generation
tasks.jacocoTestReport {
    dependsOn(tasks.test)

    reports {
        // XML report required by SonarQube
        xml.required.set(true)
        xml.outputLocation.set(file("${layout.buildDirectory.get()}/reports/jacoco/test/jacocoTestReport.xml"))

        // HTML report for local viewing
        html.required.set(true)
        html.outputLocation.set(file("${layout.buildDirectory.get()}/reports/jacoco/test/html"))

        // CSV report (optional)
        csv.required.set(false)
    }
}

// Optional: JaCoCo verification task (enforce coverage thresholds)
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            // Rule for overall project coverage
            limit {
                minimum = "0.00".toBigDecimal()  // Start with 0%, increase gradually
            }
        }

        rule {
            // Rule for package-level coverage
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
// SonarQube Configuration
// ===========================================

/**
 * SonarQube static code analysis configuration
 *
 * Setup Options:
 * 1. SonarCloud (Recommended for Open Source):
 *    - Free for public repositories
 *    - No infrastructure needed
 *    - Set SONAR_TOKEN and SONAR_ORGANIZATION in environment or gradle.properties
 *    - Run: ./gradlew sonar -Dsonar.host.url=https://sonarcloud.io
 *
 * 2. Local SonarQube Server:
 *    - Start server: docker run -d -p 9000:9000 sonarqube:lts-community
 *    - Access: http://localhost:9000 (admin/admin)
 *    - Generate token in UI: User > My Account > Security > Generate Tokens
 *    - Run: ./gradlew sonar -Dsonar.token=<your-token>
 *
 * For CI/CD integration, see .github/workflows/sonarqube.yml
 */
sonar {
    properties {
        // Project identification
        property("sonar.projectKey", "bedaHovorka_interlockSim")
        property("sonar.projectName", "interlockSim - Railway Interlocking Simulator")
        property("sonar.projectVersion", version.toString())

        // Source and test paths (Gradle standard layout)
        property("sonar.sources", "src/main/java")
        property("sonar.tests", "src/test/java")
        property("sonar.java.binaries", "build/classes/java/main")
        property("sonar.java.test.binaries", "build/classes/java/test")

        // Java version
        property("sonar.java.source", javaVersion)
        property("sonar.java.target", javaVersion)

        // Test results and coverage paths
        property("sonar.junit.reportPaths", "build/test-results/test")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")

        // Encoding
        property("sonar.sourceEncoding", "ISO-8859-1")

        // Exclusions (optional - exclude generated code, test utilities, etc.)
        // property("sonar.exclusions", "")
        // property("sonar.test.exclusions", "")

        // Quality gate configuration (fail build if quality gate fails)
        property("sonar.qualitygate.wait", "false")  // Set to "true" to wait for quality gate result

        // Optional: Links to project resources
        // property("sonar.links.homepage", "https://github.com/bedavs/interlockSim")
        // property("sonar.links.ci", "https://github.com/bedavs/interlockSim/actions")
        // property("sonar.links.issue", "https://github.com/bedavs/interlockSim/issues")
        // property("sonar.links.scm", "https://github.com/bedavs/interlockSim")
    }
}

// Make sonar task depend on test to ensure test results are available
tasks.named("sonar") {
    dependsOn(tasks.test)
}

// ===========================================
// Deprecation Analysis Tasks
// ===========================================

/**
 * Task: checkDeprecations
 * Analyze code for deprecated API usage and generate report
 * Compiles both main and test sources with deprecation warnings enabled
 */
tasks.register("checkDeprecations") {
    group = "verification"
    description = "Check for deprecated API usage and generate report"

    dependsOn("clean", "compileJava", "compileTestJava")

    doLast {
        println("""
            |
            |Deprecation Analysis Complete
            |=============================
            |Review compiler output above for deprecation warnings
            |
            |To save detailed report to file:
            |  ./gradlew clean compileJava compileTestJava 2>&1 | tee build/reports/deprecation-report.txt
            |
            |For jDisco library analysis:
            |  cd jdisco && mvn clean compile -Dmaven.compiler.showDeprecation=true 2>&1 | tee ../build/reports/deprecation-jdisco.txt
            |
        """.trimMargin())
    }
}
