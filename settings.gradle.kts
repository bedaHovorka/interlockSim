/*
 * settings.gradle.kts
 *
 * Gradle settings for interlockSim project
 * Migrated from Apache Ant + Ivy build system in 2026
 */

pluginManagement {
	val ktlintPluginVersion: String by settings
	val detektPluginVersion: String by settings
	val kotlinVersion: String by settings
	val burstVersion: String by settings
	val mokkeryVersion: String by settings
	plugins {
		id("org.jlleitschuh.gradle.ktlint") version ktlintPluginVersion
		id("io.gitlab.arturbosch.detekt") version detektPluginVersion
		id("org.jetbrains.kotlin.jvm") version kotlinVersion
		id("org.jetbrains.kotlin.multiplatform") version kotlinVersion
		id("app.cash.burst") version burstVersion
		id("dev.mokkery") version mokkeryVersion
	}
}

rootProject.name = "interlockSim"

include(":core")
include(":core-test")
include(":dispatcher-agent")
include(":desktop-ui")

// :fast-sim is a linuxX64 native CLI binary and cannot be built on non-Linux hosts
// (it requires libxml2 native headers and a Kotlin/Native linuxX64 toolchain).
// Defined here once; consumed in build scripts via `val isLinuxHost: Boolean by gradle.extra`.
val isLinuxHost = System.getProperty("os.name").lowercase().contains("linux")
gradle.extra["isLinuxHost"] = isLinuxHost
if (file("fast-sim/build.gradle.kts").exists()) {
	include(":fast-sim")
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
	repositories {
		mavenLocal() // For kDisco local development (highest priority)

		val githubUsername = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull
		val githubToken = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.key").orNull

		if (!githubUsername.isNullOrEmpty() && !githubToken.isNullOrEmpty()) {
			maven {
				name = "GitHubPackages-kdisco"
				url = uri("https://maven.pkg.github.com/bedaHovorka/kdisco")
				credentials {
					username = githubUsername
					password = githubToken
				}
			}
		} else {
			logger.warn("GitHub Packages credentials not available. kDisco must be in mavenLocal().")
		}

		mavenCentral()
	}
}

// Enable build cache for faster incremental builds
// Cache persists between builds and speeds up no-op builds significantly
buildCache {
    local {
        isEnabled = true
        directory = File(rootDir, ".gradle/build-cache")
    }
}
