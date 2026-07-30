/*
 * buildSrc/build.gradle.kts
 *
 * Build-logic support module (Issue #823, SP2c.0). Hosts Sp2cCoreGuard, the git-diffing
 * logic behind the root `checkCoreUntouchedBySp2c` task, as a plain Kotlin object so it is
 * unit-testable with JUnit 5 directly (temp git repos, no Gradle build spin-up required).
 *
 * buildSrc is a Gradle-standard mechanism: its main sources are compiled automatically and
 * put on every build script's classpath, but its own `test` task does NOT run automatically
 * as part of an unrelated outer build invocation (verified empirically before adding this
 * module) — so this test suite never slows down routine `./gradlew build`/`check` runs.
 * Run it explicitly with `./gradlew :buildSrc:test`.
 */

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    // Kept in sync manually with junitJupiterVersion/junitPlatformVersion in the root
    // gradle.properties; buildSrc is an isolated build and cannot read the root project's
    // version catalog directly.
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
}
