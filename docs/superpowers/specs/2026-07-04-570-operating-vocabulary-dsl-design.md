# Design: Operating-Vocabulary DSL (#570)

**Issue:** [bedaHovorka/interlockSim#570](https://github.com/bedaHovorka/interlockSim/issues/570)  
**Parent:** [bedaHovorka/interlockSim#533](https://github.com/bedaHovorka/interlockSim/issues/533) — SP3 Operating Language & Inter-Agent Protocol  
**Canonical authority:** [#532](https://github.com/bedaHovorka/interlockSim/issues/532) issue body
(Goal 10) plus its "Superseding plan — 2026-07-06" comment. Where this document conflicts with
them, the #532 body wins.  
**Status:** Final design (2026-07-06), aligned with the updated #532 description. Ready for
implementation.

---

## 1. Summary

This slice defines the first minimal operating vocabulary for SP3. The implementation will add an
`interlocksim.lang.vocab` package containing:

- a sealed, serializable `Aspect` hierarchy for Czech railway signal aspects,
- serializable value types for signals, switches, blocks, tracks, routes, and movement authorities,
- Koog `@LLMDescription` annotations on the public DSL surface so LLM tools can expose clear domain
  semantics without coupling the agent to the safety kernel.

No actuator behavior, route setting, interlocking checks, or simulation state changes belong in this
slice. The vocabulary is a typed language layer consumed by later SP3 message and tool slices.

### Alignment with the updated #532 description (2026-07-06)

The final design incorporates the following decisions from the canonical #532 body and its
superseding companion plan:

1. **Module placement:** agent-facing code lives in the new **`:dispatcher-agent`** Gradle
   subproject (renamed from `:agent`). `:core` and `:fast-sim` native builds must stay free of
   agent-framework (Koog/Ollama) dependencies (SP0.6, #545). Because this vocabulary carries Koog
   `@LLMDescription` annotations, it lands in `:dispatcher-agent`, **not** `:core` (see §3).
2. **LLM role is DISPATCHER-only.** Train agents are algorithmic, never LLM-driven (decided
   2026-07-04). The vocabulary remains the shared typed language for dispatcher↔train exchanges;
   only the dispatcher side is exposed to an LLM via Koog tools.
3. **Consumers:** SP3.2 is pulled forward in parallel with SP0 and feeds **#695 (SP2b.8 — load
   station topology into the LLM context at agent start)** in addition to SP3.3 (#571) and
   SP3.5 (#573). SP3.9 (#577) grows the aspect set later.
4. **Paramount example is `vyhybna.xml`.** The v0 vocabulary only needs to express the simplified
   speed system used on that network; corridor speeds are a SP3.9 concern.
5. **Agent runtime speed constraint (recorded, out of scope here):** agent-driven simulation runs
   will be restricted to the slow end of the Goal 7 (#187) simulation-speed range (owner decision,
   #532 comment 2026-07-06). This is a `SimulationRunner` runtime concern for SP1/SP2b and does not
   affect the vocabulary types.

---

## 2. Goals and Non-Goals

### Goals

1. Provide compile-time-checked signal aspects via a sealed hierarchy.
2. Keep the speed-signalling model intentionally small: generic `Rychlost(kmh)` and `Ocekavejte(kmh)`
   rather than separate classes for every speed.
3. Use `kotlinx.serialization` for all vocabulary types.
4. Annotate all public DSL types and important properties with Koog `@LLMDescription`.
5. Keep the vocabulary independent from existing simulator internals (`RailSemaphore`,
   `RailSwitch`, `DynamicTrackBlock`, `Path`, etc.).
6. Keep `:core` and `:fast-sim` free of Koog dependencies by hosting the vocabulary in
   `:dispatcher-agent` (per the updated #532 body and SP0.6, #545).

### Non-Goals

1. Do not implement message envelopes or speech acts; those belong to SP3.3 and later.
2. Do not wire the vocabulary into route reservation, GUI, XML, or simulation code.
3. Do not enforce railway safety rules in the value types. Interlocking remains the safety authority.
4. Do not model the full Czech signalling catalogue yet; SP3.9 (#577) can extend the sealed
   hierarchy when corridor-speed scenarios are modelled.
5. Do not expose LLM tools for train agents; trains stay algorithmic (decided 2026-07-04). The
   DISPATCHER is the project's only LLM-driven role.
6. Do not implement the agent-runtime simulation-speed restriction (slow Goal 7 speeds only);
   that belongs to the SP1/SP2b runtime wiring.

---

## 3. Package and Source Location

**Package:** `interlocksim.lang.vocab`

**Module:** **`:dispatcher-agent`** — the new Gradle subproject defined by the updated #532 body
(sibling of `:core`, `:desktop-ui`, `:fast-sim`; depends on `:core`; owns the Koog/Ollama
dependencies; never depended on by `:fast-sim`).

**Expected source set:** `dispatcher-agent/src/main/kotlin/interlocksim/lang/vocab/`

Rationale: the vocabulary API carries Koog `@LLMDescription` annotations, and per #532/SP0.6
(#545) `:core` and the `:fast-sim` native build must stay free of agent-framework dependencies.
Placing the package in `:dispatcher-agent` keeps that boundary clean without needing an extra
annotation-only module. If SP3.2 is implemented before SP1.1 (#546) creates the module, SP3.2
creates a minimal `:dispatcher-agent` skeleton (Kotlin/JVM, `kotlinx.serialization` plugin, Koog
annotations dependency) that #546 then extends.

This package is intentionally separate from the legacy simulator namespace. It is the stable SP3
language namespace exposed to agents and serialized inter-agent protocol payloads. Adapter code in
later slices can translate between these vocabulary identifiers and existing simulator objects.

---

## 4. Public API Surface

### 4.1 Switch Position

```kotlin
package interlocksim.lang.vocab

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@LLMDescription("Requested switch position: PLUS for the normal/direct route, MINUS for diverging.")
enum class SwitchPosition {
    @SerialName("plus")
    @LLMDescription("Normal or direct switch position.")
    PLUS,

    @SerialName("minus")
    @LLMDescription("Diverging switch position.")
    MINUS,
}
```

### 4.2 Signal Aspects

Use a sealed interface so every `when (aspect)` over the operating vocabulary is exhaustive.
Use stable `@SerialName` values because these names become protocol data, not implementation detail.

```kotlin
@Serializable
@LLMDescription("Czech railway signal aspect (návěst) used by the SP3 operating language.")
sealed interface Aspect {
    @Serializable
    @SerialName("stuj")
    @LLMDescription("Stůj: stop at the signal.")
    data object Stuj : Aspect

    @Serializable
    @SerialName("volno")
    @LLMDescription("Volno: proceed at line speed; the following signal also permits movement.")
    data object Volno : Aspect

    @Serializable
    @SerialName("vystraha")
    @LLMDescription("Výstraha: proceed and expect stop at the next main signal.")
    data object Vystraha : Aspect

    @Serializable
    @SerialName("rychlost")
    @LLMDescription("Rychlost N km/h: proceed with the specified speed limit.")
    data class Rychlost(
        @LLMDescription("Permitted speed in kilometres per hour.")
        val kmh: Int,
    ) : Aspect

    @Serializable
    @SerialName("ocekavejte")
    @LLMDescription("Očekávejte rychlost N km/h at the next signal.")
    data class Ocekavejte(
        @LLMDescription("Expected speed at the next signal in kilometres per hour.")
        val kmh: Int,
    ) : Aspect

    @Serializable
    @SerialName("privolavaci_navest")
    @LLMDescription("Přivolávací návěst: pass a stop signal in degraded mode, on sight.")
    data object PrivolavaciNavest : Aspect

    @Serializable
    @SerialName("posun_dovolen")
    @LLMDescription("Posun dovolen: shunting movement is permitted.")
    data object PosunDovolen : Aspect

    @Serializable
    @SerialName("posun_zakazan")
    @LLMDescription("Posun zakázán: shunting movement is prohibited.")
    data object PosunZakazan : Aspect
}
```

### 4.3 Identifier Value Types

Simple identifiers should be `@JvmInline value class` wrappers around strings. They remain cheap at
runtime while preventing accidental interchange of signals, switches, blocks, and tracks.

```kotlin
@Serializable
@JvmInline
@LLMDescription("Identifier of a signal or semaphore in the operating vocabulary.")
value class SignalId(
    @LLMDescription("Human-readable signal name, for example L1 or S2.")
    val name: String,
)

@Serializable
@JvmInline
@LLMDescription("Identifier of a railway switch or point.")
value class SwitchId(
    @LLMDescription("Human-readable switch name or number.")
    val name: String,
)

@Serializable
@JvmInline
@LLMDescription("Identifier of a block section; at most one train may occupy it.")
value class BlockId(
    @LLMDescription("Human-readable block identifier.")
    val name: String,
)

@Serializable
@JvmInline
@LLMDescription("Identifier of a station or line track.")
value class TrackId(
    @LLMDescription("Track number or label.")
    val name: String,
)
```

### 4.4 Route and Authority Value Types

Composite types are immutable data classes. They carry operating intent only; they do not claim that
the interlocking has accepted or locked the route.

```kotlin
@Serializable
@LLMDescription("Required setting for one switch in a train route.")
data class SwitchSetting(
    @LLMDescription("Switch to set.")
    val switch: SwitchId,

    @LLMDescription("Requested switch position.")
    val position: SwitchPosition,
)

@Serializable
@LLMDescription("A train route (vlaková cesta) from one signal to another.")
data class TrainRoute(
    @LLMDescription("Signal where the route begins.")
    val from: SignalId,

    @LLMDescription("Signal where the route ends.")
    val to: SignalId,

    @LLMDescription("Switch settings that form the running path.")
    val running: List<SwitchSetting>,

    @LLMDescription("Flank-protection switch settings required by the route.")
    val flank: List<SwitchSetting> = emptyList(),

    @LLMDescription("Track or line selected by this route, when known.")
    val track: TrackId? = null,

    @LLMDescription("Ordered block sections covered by the route.")
    val blocks: List<BlockId>,
)

@Serializable
@LLMDescription("Authority for a train to move up to a defined limit.")
data class MovementAuthority(
    @LLMDescription("Signal or marker that bounds the authority.")
    val target: SignalId,

    @LLMDescription("Maximum permitted speed under this authority in kilometres per hour.")
    val speedLimitKmh: Int,

    @LLMDescription("Last block section covered by this authority.")
    val endOfAuthority: BlockId,
)
```

---

## 5. Validation Policy

Keep constructors permissive in the first slice. The vocabulary package should not become a safety
kernel or timetable validator. However, tests should document basic expectations:

- identifiers preserve their wrapped value,
- `Aspect` serialization round-trips every object and parameterized aspect,
- `TrainRoute` and `MovementAuthority` serialization round-trip nested value types,
- exhaustive `when` handling is possible without an `else` branch inside tests or sample code.

If implementation adds explicit speed validation later, prefer a small domain helper such as
`SupportedSpeeds` over hard-coded checks spread across constructors.

---

## 6. Serialization Shape

Implementation should use `Json` round-trip tests to lock down the wire shape. A stable shape matters
because later SP3 speech acts will embed these values.

Recommended characteristics:

- stable `@SerialName` discriminators for `Aspect`,
- value classes serialize as their wrapped scalar values,
- data classes use descriptive property names (`switch`, `position`, `from`, `to`, `blocks`,
  `speedLimitKmh`, `endOfAuthority`),
- no references to existing simulator object identities or memory addresses.

The exact JSON discriminator configuration can be selected in the SP3 protocol layer. The vocabulary
types should be compatible with the repository's standard `kotlinx.serialization` defaults.

---

## 7. Dependency Notes

The vocabulary needs two compile-time dependencies, both owned by `:dispatcher-agent`:

1. `kotlinx.serialization` runtime and compiler plugin support.
2. Koog annotations that provide `ai.koog.agents.core.tools.annotations.LLMDescription`.

The earlier open question ("if Koog is too large for `:core`…") is resolved by the updated #532
description: Koog never enters `:core`. `:dispatcher-agent` owns all Koog/Ollama dependencies, so
the public vocabulary API can carry `@LLMDescription` without leaking agent-framework dependencies
into `:core` or the `:fast-sim` native build.

---

## 8. Traceability to SP3 Parent Design

| SP3 parent vocabulary | This slice |
|---|---|
| `Aspect ::= STUJ | VOLNO | VYSTRAHA | RYCHLOST(n) | OCEKAVEJTE(n) | ...` | `sealed interface Aspect` with eight starter cases |
| `SwitchSetting ::= Switch ("plus" | "minus")` | `SwitchSetting(SwitchId, SwitchPosition)` |
| `TrainRoute ::= fromSignal toSignal SwitchSetting+ FlankProtection*` | `TrainRoute(from, to, running, flank, track, blocks)` |
| `MovementAuthority ::= targetSignal speedLimit endOfAuthority` | `MovementAuthority(target, speedLimitKmh, endOfAuthority)` |
| LLM-facing Koog tools | `@LLMDescription` on vocabulary classes and properties |

Downstream consumers per the #532 superseding plan: #571 (SP3.3 message protocol), #573 (SP3.5
DISPATCHER Koog ToolSets), **#695 (SP2b.8 topology-into-LLM-context — depends on this slice)**,
#575 (SP3.7 benchmark), and #577 (SP3.9 aspect-set growth, future).

---

## 9. Implementation Checklist for Follow-Up

- [ ] Create the `:dispatcher-agent` module skeleton if #546 (SP1.1) has not landed yet
      (Kotlin/JVM, depends on `:core`, `kotlinx.serialization` plugin, Koog annotations).
- [ ] Create `interlocksim.lang.vocab` in `dispatcher-agent/src/main/kotlin/`.
- [ ] Implement `SwitchPosition`, `Aspect`, identifier value classes, `SwitchSetting`,
      `TrainRoute`, and `MovementAuthority`.
- [ ] Add focused serialization round-trip tests for all types in `:dispatcher-agent`.
- [ ] Confirm the package remains independent of simulator internals and that `:core` and
      `:fast-sim` acquire no Koog dependency.
- [ ] Run `./gradlew :dispatcher-agent:test` and relevant code-quality checks.
