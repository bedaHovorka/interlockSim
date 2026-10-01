import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty

/**
 * Per-module input of the `interlocksim.sonar-module` convention plugin (Issue #1018).
 *
 * Both values are read only when the root :sonar task computes the analysis properties.
 */
interface SonarModuleExtension {
	/** This module's own JaCoCo XML report; the plugin adds the cross-module aggregate. */
	val coverageReport: RegularFileProperty

	/** Module-relative JUnit result directories, for `sonar.junit.reportPaths`. */
	val junitReportPaths: ListProperty<String>
}
