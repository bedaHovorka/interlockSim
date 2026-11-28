/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.executor

import kotlinx.serialization.Serializable

/**
 * The model profile an LLM run ran on, as Ollama reported it at run start (Issue #1342).
 *
 * Lives in `executor` — the package whose `/api/show` fetch produces it — so the executor's
 * `ModelfileHashSource` depends on no `planner` type (the one executor→planner import the 2026-11-27
 * review round flagged), while the run snapshot in `planner` serializes this record as its
 * [DispatcherRunSnapshot.modelProfile] field, as the established `planner`-imports-`executor`
 * direction already does everywhere else.
 *
 * The dispatcher's output cap cannot be sent through Koog 1.1.1 (`maxTokens` never reaches the
 * wire), so it lives in the Modelfile of a derived local Ollama tag as `PARAMETER num_predict`.
 * This record names that tag and pins exactly which Modelfile ran, so two runs on the same tag name
 * but a rebuilt Modelfile are never pooled as one configuration.
 *
 * @property tag The Ollama model tag the run used (`OllamaExecutorConfig.modelName`).
 * @property modelfileSha256 SHA-256 of the `modelfile` string `/api/show` returned for [tag], as
 *   UTF-8 bytes, lowercase hex; `null` when the call failed — *not measured*, never "no Modelfile".
 * @property numPredict The `num_predict` value of the tag's parameters; `null` when the call failed,
 *   the Modelfile sets none, or the configured value is non-positive (Ollama's `-1` means no cap —
 *   then Ollama's own default applies and no cap is configured).
 */
@Serializable
data class ModelProfileRecord(
	val tag: String,
	val modelfileSha256: String?,
	val numPredict: Int?
)
