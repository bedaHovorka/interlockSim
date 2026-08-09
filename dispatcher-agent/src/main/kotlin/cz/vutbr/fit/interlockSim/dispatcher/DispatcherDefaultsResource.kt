/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.io.InputStream
import java.util.Properties

private val logger = KotlinLogging.logger {}

/**
 * Loads the committed `dispatcher-defaults.properties` classpath resource that lets the dispatcher's
 * defaults be *configured* rather than hardcoded (#834, SP2c.11).
 *
 * ## Why this exists
 *
 * Before this class, every dispatcher default ([DispatcherRunConfig.Companion]'s `DEFAULT_*`
 * constants and [cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig.Companion]'s
 * `DEFAULT_MODEL_NAME`/`DEFAULT_TEMPERATURE`) was a Kotlin `const val`: changing one meant editing
 * and recompiling source. #834's acceptance criterion requires the sweep's *chosen* defaults to be
 * committed as configuration instead. This class is the loader for that configuration file: it
 * resolves the classpath resource into a `key -> value` map that
 * [DispatcherRunConfig.fromProperties] and
 * [cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig.default] consult as the
 * middle tier of their precedence chain (`JVM system property > this file > code constant`).
 *
 * ## Failure handling
 *
 * A missing, unreadable, or syntactically malformed resource is a WARN, never an exception —
 * consistent with [DispatcherRunConfig]'s existing "log and fall back" policy for a single bad `-D`
 * value. The distinction is between *whole-file* failures (handled here: [lookup] then returns
 * `null` for every key, indistinguishable to callers from "this file does not override anything")
 * and *single-value* parse failures (handled by each caller's own precedence-resolution code, since
 * the target type of each key — [Long], [Int], [Float], [String] — is caller knowledge, not this
 * loader's).
 *
 * A key present in the file but not in [RECOGNIZED_KEYS] is ignored rather than rejected: logged
 * once at WARN (to surface a likely typo) but never a build/run failure, so this file stays
 * forward-compatible with a knob that is documented but not yet wired.
 *
 * @since Issue #834 (SP2c.11 — commit the sweep's chosen defaults as configuration, not as
 *   hardcoded `const val`s)
 */
class DispatcherDefaultsResource private constructor(
	private val values: Map<String, String>
) {
	/** The committed value for [key], or `null` if the file did not set it (or could not be read). */
	fun lookup(key: String): String? = values[key]

	companion object {
		/**
		 * Classpath-relative resource name. Resolved via [Class.getResourceAsStream] against this
		 * class's own package, i.e.
		 * `cz/vutbr/fit/interlockSim/dispatcher/dispatcher-defaults.properties` — the same
		 * package-relative-resource convention `ConstrainedJsonEmissionStrategy` already uses for
		 * `action_batch_schema.json`.
		 */
		private const val RESOURCE_NAME = "dispatcher-defaults.properties"

		/**
		 * Every property key this file is allowed to carry. Kept here as the one authoritative list
		 * (rather than re-deriving it separately in each consumer) so [parse]'s unknown-key filter
		 * has a single source of truth. Deliberately excludes [DispatcherRunConfig.PROP_RUN_ID] and
		 * [DispatcherRunConfig.PROP_RUNS_ROOT]: those two are always per-run identifiers/paths, never
		 * a "default" a committed file could sensibly ship.
		 */
		val RECOGNIZED_KEYS: Set<String> =
			setOf(
				DispatcherRunConfig.PROP_MODEL,
				DispatcherRunConfig.PROP_TEMPERATURE,
				DispatcherRunConfig.PROP_TICK_PERIOD_MS,
				DispatcherRunConfig.PROP_HISTORY_N,
				DispatcherRunConfig.PROP_MAX_ACTIONS_PER_TICK,
				DispatcherRunConfig.PROP_INFERENCE_TIMEOUT_SECONDS
			)

		/**
		 * The real resource shipped inside the jar, loaded once and cached for the JVM's lifetime —
		 * the file is immutable at runtime, nothing rewrites it between requests, so re-reading it
		 * on every [DispatcherRunConfig.fromProperties] call would be pure waste.
		 */
		val shipped: DispatcherDefaultsResource by lazy {
			fromStream(DispatcherDefaultsResource::class.java.getResourceAsStream(RESOURCE_NAME))
		}

		/**
		 * Parses [stream] into a [DispatcherDefaultsResource], or falls back to an empty one (every
		 * [lookup] returns `null`) on any failure. `null` itself means "resource not found on the
		 * classpath" — what [Class.getResourceAsStream] returns for a missing entry — and is handled
		 * identically to a read/parse failure: WARN, then behave as if the file were absent.
		 *
		 * `internal` rather than `private` so tests can exercise "absent file" / "malformed file"
		 * directly, without needing a real broken classpath resource to do it.
		 */
		internal fun fromStream(stream: InputStream?): DispatcherDefaultsResource {
			if (stream == null) {
				logger.warn {
					"Missing classpath resource '$RESOURCE_NAME'; using code fallback defaults for " +
						"every dispatcher setting."
				}
				return DispatcherDefaultsResource(emptyMap())
			}
			return try {
				stream.use { parse(it) }
			} catch (e: IOException) {
				logFailure(e)
				DispatcherDefaultsResource(emptyMap())
			} catch (e: IllegalArgumentException) {
				// java.util.Properties#load throws IllegalArgumentException (not IOException) for
				// a malformed \uXXXX escape — still a syntax failure of the whole file, same
				// "WARN and fall back" handling as an IOException.
				logFailure(e)
				DispatcherDefaultsResource(emptyMap())
			}
		}

		private fun logFailure(e: Exception) {
			logger.warn(e) {
				"Failed to parse classpath resource '$RESOURCE_NAME'; using code fallback " +
					"defaults for every dispatcher setting."
			}
		}

		private fun parse(stream: InputStream): DispatcherDefaultsResource {
			val properties = Properties()
			properties.load(stream)
			val recognized = mutableMapOf<String, String>()
			val unknown = mutableListOf<String>()
			for (name in properties.stringPropertyNames()) {
				if (name in RECOGNIZED_KEYS) {
					recognized[name] = properties.getProperty(name)
				} else {
					unknown += name
				}
			}
			if (unknown.isNotEmpty()) {
				logger.warn { "Ignoring unrecognized key(s) in '$RESOURCE_NAME': ${unknown.sorted()}" }
			}
			return DispatcherDefaultsResource(recognized)
		}
	}
}
