# SP1.2 AIAgent/AgentService Skeleton Documentation

**Issue:** #547
**Phase:** SP1.2 (Koog 1.0.0 dependency + skeleton)
**Goal:** Goal 10 — AI-driven railway dispatcher
**Last Updated:** 2026-07-11 (Koog 1.0.0, tool-capable model selection)

## Overview

This document describes the skeleton implementation of Koog-based agent infrastructure for the railway interlocking simulator.

### Purpose

SP1.2 establishes the basic structure and contracts for:
- **AgentService** — factory for creating Koog agents
- **KoogDispatchAgent** — agent interface for dispatch decisions
- **DomainTool** — bridge between railway domain and Koog tool framework
- **Koin DI wiring** — lightweight, Spring-Boot-free dependency injection

## Architecture

### Key Components

```
DomainTool (Interface)
    └─ Bridge to perception/actuator ports (SP1.3+)
    └─ Holds name, description, execute() method

AgentService (Interface)
    └─ DefaultAgentService (Implementation)
       └─ Responsible for bootstrapping agents
       └─ Accepts tool list + model config

KoogDispatchAgent (Interface)
    └─ KoogDispatchAgentImpl (Implementation)
       └─ Minimal skeleton (no-op decideAsync in SP1.2)
       └─ Ready for LLM integration in SP1.6
```

### Design Decisions

1. **No Spring Boot** — Uses Koin (lightweight, Kotlin-native) for DI
2. **Separation of concerns** — AgentService creates agents, AgentLoopDriver runs them
3. **Tool pattern** — DomainTool interface enables Koog tool registration (SP1.6)
4. **Skeleton-first** — Minimal working code now, logic in later phases

## SP1 Phasing

| Phase | Issue | Responsibility |
|-------|-------|-----------------|
| SP1.2 | #547  | Koog dep + skeleton (THIS COMMIT) |
| SP1.3 | #548  | Koin DI integration + per-context agent instances |
| SP1.4 | #549  | Perception/actuator port tool adapters |
| SP1.5 | #550  | Ollama executor backend + model config |
| SP1.6 | #551  | Full Koog tool definitions + LLM decision logic |

## Usage (Future)

```kotlin
// Once SP1.3+ wiring is complete:
val service: AgentService = get()
val agent = service.createDispatchAgent(
    modelName = "mistral",
    tools = listOf(
        signalAspectTool,
        blockOccupancyTool,
        trainPositionTool
    ),
    systemPrompt = "You are a railway dispatcher..."
)

// Then in AgentLoopDriver:
val decisions = agent.decideAsync(observation)
```

## File Structure

```
dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/
├── agents/
│   ├── DomainTool.kt                  # Interface for domain tools
│   ├── AgentService.kt                # Interfaces for agent creation
│   ├── KoogDispatchAgentImpl.kt        # Agent skeleton implementation
│   └── DefaultAgentService.kt         # Service factory skeleton
└── di/
    └── DispatcherAgentModule.kt       # Koin module with AgentService binding
```

## Testing

Skeleton tests verify:
- AgentService creates agent instances ✓
- Koin DI wiring provides singleton AgentService ✓
- Agent accepts tools and model configuration ✓
- Agent returns empty decisions (SP1.2 no-op) ✓

Tests use **tool-capable models per GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md:**
- **qwen2.5:7b-instruct** (top pick: strong tool-calling + multilingual)
- **llama3.1:8b** (close second: mature function calling)
- **gemma3:4b** (fast fallback: ≤ 4B footprint)

Rejected non-tool models (e.g., llama2): no native tool-calling support, unsuitable for dispatcher agent role.

Real LLM integration tests come in SP1.6 (#551).

## Koog Dependency

**Koog 1.0.0** (first stable release, 2026-07-XX):
- **Tool calling:** Reliably emits valid JSON for Koog tool registration
- **OllamaModels curated set:** Pre-configured model constants (verify in SP1.3)
- **Structured output validation:** Malformed tool calls caught before dispatcher sees them

Koog 1.0 ships a curated `OllamaModels` set (`OllamaModels.Meta.*`, `OllamaModels.Alibaba.*`, etc.); 
any model not pre-listed can be constructed via `LLModel`/`OllamaModelCard` directly. This mapping 
is finalized in SP1.5 (#550, model configuration wiring phase).

## Spring Boot Rationale

Original roi-hunter-assignment used Spring Boot for agent orchestration. This project uses:
- **Koin** instead of Spring DI (lightweight, Kotlin-native)
- **Direct kDisco integration** (no Spring WebFlux/async abstractions)
- **Per-context agent instances** (scoped to DefaultSimulationContext)

This keeps the agent framework minimal and focused on railway dispatch logic.

## Next Steps

### SP1.3 (#548) — Koin Per-Context Agent Binding
- Add scoped KoogDispatchAgent binding to DispatcherAgentModule
- Wire perception/actuator ports as tool factories
- Inject agent into AgentLoopDriver

### SP1.4 (#549) — Domain Tool Adapters
- Implement perception tool adapters (signal, block, train, timetable)
- Implement actuator tool adapters (reserve path, set signal, etc.)
- Feed tools into DefaultAgentService.createDispatchAgent()

### SP1.5 (#550) — Ollama Executor
- Initialize Ollama HTTP client
- Wire model selection + temperature/top-p config
- Set up tool invocation handler

### SP1.6 (#551) — Full Koog Integration
- Construct Koog agent with full tool definitions (JSON schemas)
- Implement LLM decision logic in KoogDispatchAgentImpl.decideAsync()
- Add system prompt templates + few-shot examples
- Test with real Ollama models

## References

- **Goal 10 Design:** Issue #532 ("Superseding plan — 2026-07-06" comment)
- **SP1 Parent Issue:** #546 (SP1 Agent runtime suite)
- **Agent-Architect:** TEAM.md role for agent system design
- **Traffic-Simulation-Expert:** TEAM.md role for domain arbitration
