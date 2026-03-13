/*
 * settings.gradle.kts
 *
 * Gradle settings for interlockSim project
 * Migrated from Apache Ant + Ivy build system in 2026
 */

rootProject.name = "interlockSim"

include(":core")
include(":desktop-ui")

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
