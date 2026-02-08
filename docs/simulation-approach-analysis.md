# interlockSim — Simulation Backend Approach Analysis

## Context

interlockSim is a railway interlocking simulator currently built on **jDisco** (Java, process-interaction paradigm by Keld Helsgaun). The goal is to modernize the stack while preserving the domain model. Three simulation engines are candidates: **jDisco** (original), **DSOL** (TU Delft, Java), and **Kalasim** (Kotlin, Holger Brandl). **kDisco** is a Kotlin Multiplatform abstraction layer (expect/actual) that can delegate to any of them.

The domain model uses process-interaction primitives: `Process`, `hold()`, `passivate()`, `activate()`, `waitUntil()`, `Head`, `Variable`, `Continuous`.

---

## Approach 1 — Keep jDisco (as-is)

### What it means
No rewrite. interlockSim stays on jDisco 1.2 Java library. Model code stays Java or Kotlin/JVM calling jDisco directly.

### Effort
**Minimal** — zero migration work. Only maintenance of existing codebase.

### Pros
- Zero risk, zero effort — the system works today.
- Full process-interaction support — `hold()`, `passivate()`, `activate()`, `waitUntil()` all native and battle-tested.
- No abstraction overhead or mapping mismatches.

### Cons
- **JVM-only forever** — no path to Kotlin/Native, JS, or WASM.
- **Dead upstream** — jDisco is an academic library from RUC (Roskilde), no active development, no community, no Maven Central publication.
- **Java API** — model code stays in Java idioms (static methods, thread-based coroutines), no Kotlin coroutines, no `sequence{}`.
- **No experiment framework** — no built-in experiment management, statistics, animation, or unit system.
- **Dependency risk** — JAR distributed via website, not a package registry. If the website goes down, the artifact is gone.

---

## Approach 2 — kDisco + backed by jDisco

### What it means
Build the kDisco Kotlin Multiplatform common API (`commonMain`). The first (and possibly only) `actual` implementation delegates to jDisco on JVM. interlockSim is rewritten to depend on kDisco's common API.

### Effort
**Medium** — kDisco common API design + jDisco bridge (~20 files, ~800 lines as prototyped in chat 4). interlockSim model rewrite from Java jDisco imports → Kotlin kDisco imports. Open items: `waitUntil` bridge semantics, `activate`/`reactivate` static-vs-instance, simulation context lifecycle.

### Pros
- **Model becomes portable** — interlockSim written against `commonMain`, ready for future backend swaps without touching model code.
- **Incremental** — jDisco is proven, so the simulation correctness is unchanged; only the API surface shifts.
- **Kotlin idioms** — model code becomes idiomatic Kotlin (extension functions, coroutine-style, Koin DI).
- **Future-proofing** — adding a Native/JS backend later requires only new `actual` implementations, not model changes.

### Cons
- **Still JVM-only in practice** — until a non-jDisco actual is written, the multiplatform promise is theoretical.
- **Bridging complexity** — jDisco uses Java thread-based coroutines internally; wrapping them in Kotlin expect/actual can leak abstraction (thread-local state, static scheduling methods).
- **Double maintenance** — kDisco API + jDisco bridge both need upkeep, with jDisco still a dead upstream dependency.
- **Unresolved open items** — `waitUntil` bridge, static method delegation, simulation lifecycle initialization all flagged as TODOs.

---

## Approach 3 — Only DSOL (direct)

### What it means
Rewrite interlockSim directly against DSOL's Java API. No kDisco layer. Model uses DSOL's `DEVSSimulatorInterface`, `SimEvent`, `ModelInterface`, `Experiment`.

### Effort
**High** — full model rewrite from process-interaction to event-scheduling paradigm. Every `Process` subclass becomes event handlers. `hold()`/`passivate()`/`activate()` patterns must be decomposed into `scheduleEvent()` chains. Conceptual shift, not just API mapping.

### Pros
- **Production-grade ecosystem** — DSOL has animation framework, experiment management, DJUNITS (type-safe physical units), statistics, replication support.
- **Proven in transport** — OpenTrafficSim (Dutch national traffic simulator) is built on DSOL event-scheduling.
- **Active development** — TU Delft maintains it, published on Maven Central, documented with manual + examples.
- **Event-scheduling fits railway** — signal state changes, route locking/unlocking, train movement are naturally event-driven (as OTS demonstrates).

### Cons
- **Paradigm mismatch** — interlockSim's model is process-interaction (`hold`, `passivate`, `activate`). DSOL's process-interaction support is exotic (bytecode interpreter, zero tutorials, no known users). Practical DSOL = event-scheduling only.
- **Complete rewrite** — every simulation class must be redesigned, not just re-imported. High risk of introducing bugs in the paradigm translation.
- **Java-only** — DSOL is a JVM library. No multiplatform path.
- **Verbose** — DSOL's event-scheduling API is more verbose than process-interaction for modeling entity behavior (schedule chains vs. sequential `hold`/`activate`).
- **Loss of process intuition** — train processes that read naturally as "move → wait → signal → proceed" become scattered event callbacks.

---

## Approach 4 — kDisco + backed by DSOL

### What it means
kDisco common API stays process-interaction style. The JVM `actual` implementation translates kDisco's `hold()`/`passivate()`/`activate()` into DSOL event-scheduling calls under the hood.

### Effort
**Very high** — requires building a process-interaction layer on top of DSOL's event-scheduling engine. This is essentially re-implementing what DSOL's own exotic bytecode-interpreter process module does, but via Kotlin coroutines or virtual threads. The kDisco-to-DSOL bridge is the hardest of all bridge options.

### Pros
- **Best of both worlds (in theory)** — model stays process-interaction (kDisco API), engine gets DSOL's experiment/animation/statistics ecosystem.
- **DSOL ecosystem access** — DJUNITS, animation, replication, all available underneath.
- **Model portability** — same commonMain model as approach 2, just different backend.

### Cons
- **Extreme bridging complexity** — mapping process-interaction semantics (suspend/resume a process at `hold()`) onto event-scheduling (schedule a callback at time T) requires a coroutine-to-event bridge. This is non-trivial concurrency engineering.
- **Unproven** — no known project has successfully bridged process-interaction on top of DSOL event-scheduling in Kotlin. DSOL's own attempt uses a JVM bytecode interpreter, which signals the difficulty.
- **Debugging nightmare** — two layers of abstraction between model code and actual simulation execution. Stack traces cross kDisco → coroutine bridge → DSOL event queue.
- **Still JVM-only** — DSOL is Java, so this backend only works on JVM. The multiplatform benefit comes only if you also build other backends.
- **Maintenance burden** — the most complex bridge of any option, with the most surface area for bugs.

---

## Approach 5 — Only Kalasim (direct)

### What it means
Rewrite interlockSim directly against Kalasim's Kotlin API. No kDisco layer. Model uses Kalasim's `Component`, `Resource`, `State`, `Environment`, `sequence{}`.

### Effort
**Medium** — Kalasim's API is the closest to jDisco's mental model among the alternatives. `Component` ≈ `Process`, `hold()` exists, `request()`/`release()` for resources, `wait()` for state changes. The mapping is more natural than DSOL. Still requires rewriting every simulation class, but the paradigm stays process-interaction.

### Pros
- **Kotlin-native** — idiomatic Kotlin, coroutine-based, `sequence{}` blocks for process definitions. Modern, clean API.
- **Closest API match to jDisco** — `hold()`, `passivate()`, `activate()` concepts all present. Process-interaction paradigm preserved.
- **Rich features** — built-in resource management, state tracking, monitoring/statistics, real-time mode, dependency injection (Koin under the hood).
- **Active project** — maintained by Holger Brandl, published on Maven Central, documentation at kalasim.org.
- **Lower rewrite risk** — paradigm stays the same, reducing the chance of introducing conceptual bugs.

### Cons
- **JVM-only** — Kalasim runs on JVM (uses Koin, coroutines). No Kotlin/Native or JS target.
- **Vendor lock-in** — direct dependency on Kalasim's API. If you later want to switch engines or go multiplatform, another rewrite is needed.
- **Smaller community** — Kalasim is a one-person project. Less battle-tested than DSOL at scale.
- **No animation framework** — unlike DSOL, Kalasim has no built-in visualization/animation.
- **Subtle API differences** — Kalasim's `Component` is not identical to jDisco's `Process`. `waitUntil` maps to `standby()` (polled every event) or `wait(state)` (event-driven). Some translation required.

---

## Approach 6 — kDisco + backed by Kalasim

### What it means
kDisco common API (process-interaction). JVM `actual` delegates to Kalasim. As prototyped in chat 3: `Process` extends Kalasim's `Component`, `Simulation` wraps `Environment`, resources/states map naturally.

### Effort
**Medium** — the kDisco-to-Kalasim bridge is the most natural of all backend options (prototyped at ~533 lines, chat 3). API concepts align well. `hold()` → `hold()`, `passivate()` → `passivate()`, `activate()` → `activate()`. The `waitUntil` has two paths: `standby()` (polled) or `wait(state)` (event-driven).

### Pros
- **Natural bridge** — Kalasim's API is already close to jDisco/kDisco. The actual implementation is thin, not a complex translation layer.
- **Model portability** — same commonMain as approaches 2 and 4. Model code doesn't change.
- **Already prototyped** — 533-line implementation exists from chat 3 (Process, Simulation, Continuous, Koin integration). Not theoretical.
- **Kalasim features available** — Resource, State, monitoring, statistics accessible through kDisco or directly.
- **Future multiplatform** — a pure-coroutine `actual` for Native/JS/WASM can be added later without touching model.
- **Koin DI flows through** — Kalasim already uses Koin internally; kDisco-koin module integrates naturally.

### Cons
- **Still JVM-only via Kalasim** — the Native/WASM backend is a separate future effort (pure coroutines implementation).
- **Abstraction tax** — one layer between model and engine. Slightly harder to debug than direct Kalasim.
- **Two projects to maintain** — kDisco common API + Kalasim bridge + Kalasim dependency.
- **Kalasim upstream risk** — same one-person project concern as approach 5.
- **Subtle semantic gaps** — `waitUntil` polling via `standby()` is less efficient than event-driven `wait(state)`. Model authors must understand when to use which.

---

## Comparison Matrix

| Criterion | 1. jDisco | 2. kDisco+jDisco | 3. DSOL only | 4. kDisco+DSOL | 5. Kalasim only | 6. kDisco+Kalasim |
|---|---|---|---|---|---|---|
| **Migration effort** | None | Medium | High | Very High | Medium | Medium |
| **Paradigm match** | Perfect | Perfect | Poor (event-sched) | Complex bridge | Good | Good |
| **Multiplatform path** | None | Theoretical | None | Theoretical | None | Theoretical |
| **Ecosystem richness** | Minimal | Minimal | Excellent | Excellent | Good | Good |
| **Upstream health** | Dead | Dead (jDisco) | Active (TU Delft) | Active (TU Delft) | Small (1 person) | Small (1 person) |
| **Model portability** | Locked to jDisco | Portable (kDisco API) | Locked to DSOL | Portable (kDisco API) | Locked to Kalasim | Portable (kDisco API) |
| **Bridge complexity** | N/A | Medium (thread bridge) | N/A | Extreme | N/A | Low (natural fit) |
| **Already prototyped** | Exists | Partially (chat 4) | No | No | No | Yes (chat 3, 533 lines) |
| **Debug transparency** | Direct | 1 layer | Direct | 2 layers | Direct | 1 layer |
| **Risk** | Stagnation | Bridge bugs | Paradigm errors | Bridge + paradigm | Vendor lock-in | Upstream + abstraction |

---

## Recommendation Framework

### Final state (long-term target)
The final state should satisfy: model portability, viable multiplatform path, sustainable upstream, and manageable complexity.

### Temporary states (transitional)
Up to 2 approaches can serve as stepping stones toward the final state without being wasted effort.

### Key trade-off axis
- **Portability vs. simplicity**: kDisco approaches (2, 4, 6) give model portability but add abstraction. Direct approaches (1, 3, 5) are simpler but lock you in.
- **Paradigm preservation vs. ecosystem**: DSOL has the best ecosystem but the worst paradigm match. Kalasim has good paradigm match but smaller ecosystem. jDisco has perfect paradigm match but is dead.
- **Bridge difficulty**: jDisco bridge = medium (thread issues), DSOL bridge = extreme (paradigm mismatch), Kalasim bridge = low (natural API fit).

---

*Generated from analysis of 4 project conversations covering kDisco design (chat 4), DSOL rewrite research (chat 2), Kalasim backend prototype (chat 3), and Kotlin/Native architecture (chat 1).*
