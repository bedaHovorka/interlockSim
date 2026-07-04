# Design: Operating-Vocabulary DSL (#570)

**Issue:** [bedaHovorka/interlockSim#570](https://github.com/bedaHovorka/interlockSim/issues/570)  
**Parent:** [bedaHovorka/interlockSim#533](https://github.com/bedaHovorka/interlockSim/issues/533) — SP3 Operating Language & Inter-Agent Protocol  
**Status:** Design only. Ready for implementation planning.

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

### Non-Goals

1. Do not implement message envelopes or speech acts; those belong to SP3.3 and later.
2. Do not wire the vocabulary into route reservation, GUI, XML, or simulation code.
3. Do not enforce railway safety rules in the value types. Interlocking remains the safety authority.
4. Do not model the full Czech signalling catalogue yet; SP3.9 can extend the sealed hierarchy.

---

## 3. Package and Source Location

**Package:** `interlocksim.lang.vocab`

**Expected source set:** `core/src/commonMain/kotlin/interlocksim/lang/vocab/`

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
    val number: String,
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

The implementation requires two compile-time dependencies in the source set that owns the package:

1. `kotlinx.serialization` runtime and compiler plugin support.
2. Koog annotations that provide `ai.koog.agents.core.tools.annotations.LLMDescription`.

If Koog is too large for `:core`, split the annotation dependency decision before implementation.
The issue requirement still expects the public vocabulary API to carry `@LLMDescription`.

---

## 8. Traceability to SP3 Parent Design

| SP3 parent vocabulary | This slice |
|---|---|
| `Aspect ::= STUJ | VOLNO | VYSTRAHA | RYCHLOST(n) | OCEKAVEJTE(n) | ...` | `sealed interface Aspect` with eight starter cases |
| `SwitchSetting ::= Switch ("plus" | "minus")` | `SwitchSetting(SwitchId, SwitchPosition)` |
| `TrainRoute ::= fromSignal toSignal SwitchSetting+ FlankProtection*` | `TrainRoute(from, to, running, flank, track, blocks)` |
| `MovementAuthority ::= targetSignal speedLimit endOfAuthority` | `MovementAuthority(target, speedLimitKmh, endOfAuthority)` |
| LLM-facing Koog tools | `@LLMDescription` on vocabulary classes and properties |

---

## 9. Implementation Checklist for Follow-Up

- [ ] Add serialization and Koog annotation dependencies only if not already available.
- [ ] Create `interlocksim.lang.vocab` in `:core` common source.
- [ ] Implement `SwitchPosition`, `Aspect`, identifier value classes, `SwitchSetting`,
      `TrainRoute`, and `MovementAuthority`.
- [ ] Add focused common/JVM serialization tests for all types.
- [ ] Confirm the package remains independent of simulator internals.
- [ ] Run `./gradlew test` and relevant code-quality checks.
