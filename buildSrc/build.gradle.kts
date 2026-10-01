import java.util.Properties

plugins { `kotlin-dsl` }

// buildSrc has its own build; read the root gradle.properties so plugin
// versions stay declared in one place (the same keys settings.gradle.kts uses).
val rootProps = Properties().apply { file("../gradle.properties").inputStream().use { load(it) } }
val detektPluginVersion: String = rootProps.getProperty("detektPluginVersion")
val ktlintPluginVersion: String = rootProps.getProperty("ktlintPluginVersion")
val sonarPluginVersion: String = rootProps.getProperty("sonarPluginVersion")
val kotlinVersion: String = rootProps.getProperty("kotlinVersion")

repositories { gradlePluginPortal(); mavenCentral() }

dependencies {
	// The detekt and ktlint plugins react to the Kotlin plugins and load Kotlin Gradle plugin
	// types (KotlinMultiplatformExtension, ...). A plugin on this classpath only sees classes
	// on this classpath, so the Kotlin Gradle plugin has to sit here too; the module scripts
	// then apply kotlin("jvm") / kotlin("multiplatform") from it.
	// It is here only so detekt/ktlint/sonar can see KGP types: buildSrc itself compiles with
	// Gradle's embedded Kotlin, so a convention plugin must not call KGP APIs directly without
	// checking that the embedded compiler can read this KGP version.
	implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
	implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:$detektPluginVersion")
	implementation("org.jlleitschuh.gradle:ktlint-gradle:$ktlintPluginVersion")
	// interlocksim.sonar-module configures the SonarExtension, so the plugin has to be on
	// this classpath. The root build.gradle.kts therefore applies org.sonarqube without a
	// version: a plugin already on the buildSrc classpath cannot be requested with one.
	implementation("org.sonarsource.scanner.gradle:sonarqube-gradle-plugin:$sonarPluginVersion")
}
