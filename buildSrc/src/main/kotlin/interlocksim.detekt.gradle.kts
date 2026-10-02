/*
 * interlocksim.detekt — the detekt setup every module shares (Issue #1018).
 *
 * Each module still sets its own `detekt { source.setFrom(...) }`, because the source
 * roots differ per module. :fast-sim overrides `config` with detekt-strict.yml.
 */

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask

plugins {
	id("io.gitlab.arturbosch.detekt")
}

detekt {
	buildUponDefaultConfig = true
	allRules = false
	ignoreFailures = false
	config.setFrom(rootProject.file("detekt.yml"))
	baseline = rootProject.file("detekt-baseline.xml")
	parallel = true
	basePath = rootProject.projectDir.absolutePath
}

tasks.withType<Detekt>().configureEach {
	jvmTarget = "21"
	reports {
		html.required.set(true)
		xml.required.set(true)
		txt.required.set(true)
		sarif.required.set(false)
		md.required.set(false)
	}
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
	jvmTarget = "21"
}

dependencies {
	// Same version as the detekt plugin itself: the formatting rules must match the engine.
	"detektPlugins"("io.gitlab.arturbosch.detekt:detekt-formatting:${property("detektPluginVersion")}")
}
