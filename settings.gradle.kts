/*
 * settings.gradle.kts
 *
 * Gradle settings for interlockSim project
 * Migrated from Apache Ant + Ivy build system in 2026
 */

rootProject.name = "interlockSim"

include(":core")

// Enable build cache for faster incremental builds
// Cache persists between builds and speeds up no-op builds significantly
buildCache {
    local {
        isEnabled = true
        directory = File(rootDir, ".gradle/build-cache")
    }
}
