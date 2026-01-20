# Architecture Diagrams

This directory contains PlantUML source files and generated diagrams for the Railway Interlocking Simulator context architecture.

## Diagrams

### 1. Context Class Hierarchy (`context-hierarchy.puml`)

Shows the inheritance structure of context classes:
- `Context<C : Cell>` - Base interface with type parameter
- `EditingContext` - Interface for editing operations (extends `Context<NodeCell>`)
- `SimulationContext` - Interface for simulation operations (extends `Context<Cell>`, **NOT** `EditingContext`)
- `BaseContext` - Abstract base class (non-parameterized to avoid variance complexity)
- `DefaultEditingContext` - Concrete editing implementation (613 lines)
- `DefaultSimulationContext` - Concrete simulation implementation (829 lines)
- `DefaultContext` - Deprecated backward-compatibility wrapper (74 lines)

**Key Architectural Note:** `SimulationContext` does NOT extend `EditingContext`. The railway network is immutable during simulation. Transformation from editing to simulation is one-way via `ContextTransformer`.

### 2. Context Transformation Flow (`context-transformation.puml`)

Shows the workflow from editing to simulation:
1. **Editing Phase** - User edits railway network in GUI using `EditingContext`
   - Mutable network with static `NodeCell` objects
   - Add/remove/move cells, join with track blocks
2. **Transformation Phase** - `ContextTransformer` creates `SimulationContext`
   - `GridTransformer` converts static cells to dynamic wrappers
   - `RailSwitch` → `DynamicRailSwitch`
   - `RailSemaphore` → `DynamicRailSemaphore`
   - `InOut` → `DynamicInOut`
   - Identity preserved via wrapper pattern
3. **Simulation Phase** - Discrete event simulation with jDisco engine
   - Immutable network structure
   - Mutable dynamic state (signals, switches, trains)

### 3. Factory Pattern Integration (`factory-pattern.puml`)

Shows factory relationships and dependency injection:
- **Factory Interfaces:**
  - `ContextFactory` - Base factory for loading/saving contexts
  - `EditingContextFactory` - Creates editing contexts
  - `SimulationContextFactory` - Converts editing to simulation context
  - `SimulationProcessFactory` - Creates simulation processes (Generator, InOutWorker)
- **Implementations:**
  - `XMLContextFactory` - Primary factory for XML-based contexts
  - `DefaultSimulationProcessFactory` - Creates jDisco-based processes
- **Dependency Injection (Koin):**
  - Factories provided as singletons
  - Contexts created fresh (factory scope)
  - `SimulationProcessFactory` injected into `DefaultSimulationContext`

**Key Design Pattern:** Factory pattern enables dependency inversion - contexts depend on factory abstractions, not concrete simulation classes. This supports testing, flexibility, and future migration from jDisco to DSOL/Kalasim.

## Generating Diagrams

### Prerequisites

- Java 11+ installed
- PlantUML JAR file (download from https://plantuml.com/download)

### Generate PNG images

```bash
java -jar plantuml.jar -tpng *.puml
```

### Generate SVG images

```bash
java -jar plantuml.jar -tsvg *.puml
```

### Download PlantUML

```bash
wget https://github.com/plantuml/plantuml/releases/download/v1.2024.8/plantuml-1.2024.8.jar -O plantuml.jar
```

**Note:** The `plantuml.jar` file is excluded from version control via `.gitignore`. Download it locally if you need to regenerate diagrams.

## File Structure

```
docs/diagrams/
├── README.md                       # This file
├── .gitignore                      # Excludes plantuml.jar
├── context-hierarchy.puml          # PlantUML source - class hierarchy
├── context-hierarchy.png           # Generated PNG
├── context-hierarchy.svg           # Generated SVG
├── context-transformation.puml     # PlantUML source - transformation flow
├── context-transformation.png      # Generated PNG
├── context-transformation.svg      # Generated SVG
├── factory-pattern.puml            # PlantUML source - factory integration
├── factory-pattern.png             # Generated PNG
└── factory-pattern.svg             # Generated SVG
```

## Related Documentation

- `docs/CONTEXT_REFACTORING_DESIGN.md` - Context split implementation details
- `docs/FACTORY_PATTERN_IMPLEMENTATION.md` - Factory pattern summary
- `docs/STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md` - Static/dynamic separation pattern
- `docs/CONTEXT_INHERITANCE_INCOMPATIBILITY.md` - Issue #153 context inheritance architecture

## Usage in Documentation

Diagrams can be referenced in Markdown files using relative paths:

```markdown
![Context Hierarchy](diagrams/context-hierarchy.png)
```

Or using HTML for more control:

```html
<img src="diagrams/context-hierarchy.svg" alt="Context Class Hierarchy" width="800"/>
```

SVG format is recommended for documentation as it scales better and has smaller file sizes.
