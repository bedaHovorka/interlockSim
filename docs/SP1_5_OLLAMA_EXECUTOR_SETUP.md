# SP1.5 Ollama Executor Configuration Guide

**Issue:** #550  
**Phase:** SP1.5 (Configure local Ollama executor)  
**Goal:** Goal 10 — AI-driven railway dispatcher  
**Last Updated:** 2026-07-14 (Ollama CUDA support, 8GB VRAM)

## Overview

SP1.5 configures a local Ollama LLM executor for the railway dispatcher agent. This provides:
- **No external API calls** — runs locally on your machine
- **No network dependency** — works offline after initial model pull
- **Reproducible runs** — same model, temperature, and timeout settings
- **Koin-bound DI** — seamless integration with the agent framework

## Architecture

### Components

| Component | Purpose | Scope |
|-----------|---------|-------|
| `OllamaExecutorConfig` (SP1.3) | Configuration and connectivity validation | Singleton |
| `OllamaConnectivityChecker` (SP1.3) | Proof-of-connection testing | Stateless |
| `OllamaSimpleExecutor` (SP1.5) | Executor initialization and lifecycle | Singleton |
| Koog `PromptExecutor` | Koog's LLM inference interface (returned by above) | Singleton |

### Data Flow

```
OllamaSimpleExecutor (Koin singleton)
    ↓
Builds an OllamaClient at the configured endpoint (lazy, on first access)
    ↓
Wraps it in a MultiLLMPromptExecutor(LLMProvider.Ollama to client) — a PromptExecutor
    ↓
Returns to agents for LLM calls (SP1.6+)
```

(This is what Koog's `simpleOllamaAIExecutor()` convenience function does internally; we
construct it directly since that function's module, `ai.koog:prompt-executor-llms-all`, isn't
published as a standalone artifact at the pinned Koog 1.0.0 — see `OllamaSimpleExecutor`'s
class doc.)

## Setup Instructions

### 1. Option A: Native Ollama (Fastest, GPU-enabled)

**For Linux/macOS/Windows:**

1. Download and install Ollama from https://ollama.ai/
2. Start the Ollama server:
   ```bash
   ollama serve
   ```
   (Runs on `http://localhost:11434` by default)

3. Pull the recommended model in a separate terminal:
   ```bash
   ollama pull qwen2.5:7b-instruct
   ```
   (First pull: ~4.7GB, takes a few minutes; subsequent runs use cached model)

4. Run the interlocking simulator tests:
   ```bash
   ./gradlew :dispatcher-agent:integrationTest
   ```

### 2. Option B: Docker Compose (Cross-platform, CUDA support)

**Prerequisites:**
- Docker and Docker Compose installed
- For GPU: NVIDIA Container Toolkit installed (`nvidia-container-toolkit` package)

**CPU-only (works everywhere, slower):**
```bash
docker compose up -d ollama
./gradlew :dispatcher-agent:integrationTest
```

**GPU with CUDA (fast, requires NVIDIA GPU):**
```bash
# Set CUDA environment variables
export OLLAMA_CUDA=1
export OLLAMA_MAX_VRAM=8589934592  # 8GB limit (in bytes)

# Build and start Ollama with GPU support
docker compose up -d ollama

# Run tests
./gradlew :dispatcher-agent:integrationTest
```

**GPU with ROCm (for AMD GPUs):**
```bash
export OLLAMA_ROCM=1
export OLLAMA_MAX_VRAM=8589934592

docker compose up -d ollama
./gradlew :dispatcher-agent:integrationTest
```

### 3. Verify Setup

Check that Ollama is reachable:
```bash
curl http://localhost:11434/api/tags
```

Expected output: JSON list of pulled models, including `qwen2.5:7b-instruct`.

## Configuration

### OllamaExecutorConfig Defaults

| Setting | Default | Purpose |
|---------|---------|---------|
| `ollamaEndpoint` | `http://localhost:11434` | Where Ollama is running |
| `modelName` | `qwen2.5:7b-instruct` | Model for LLM decisions |
| `temperature` | `0.7` | Sampling randomness (0.0 = deterministic, 1.0 = max random) |
| `topP` | `0.9` | Nucleus sampling cutoff |
| `maxTokens` | `1024` | Max response length |
| `inferenceTimeout` | `30 seconds` | Max wall-clock time per LLM call |
| `retryAttempts` | `3` | Retries on transient failure |

**Note:** only `ollamaEndpoint` is consumed at executor-construction time (as the
`OllamaClient`'s `baseUrl`). `modelName` feeds [validateToolCapableModel]
fail-fast validation; `temperature`/`topP`/`maxTokens`/`inferenceTimeout` are per-call
`Prompt`/`LLModel` parameters in Koog's API and are threaded through when a `Prompt` is
built and executed — that wiring is SP1.6 (#551), not this executor.

### Customize via Code (SP1.6+)

```kotlin
val config = OllamaExecutorConfig(
    ollamaEndpoint = "http://my-ollama:11434",
    modelName = "mistral:7b-instruct-v0.3",
    temperature = 0.5f,
    inferenceTimeout = Duration.ofSeconds(60)
)
val executor = OllamaSimpleExecutor(config)
```

### Customize via Environment (Future)

Environment variable support planned for SP1.6+. Currently, modify `OllamaExecutorConfig.default()` in `DispatcherAgentModule.kt`.

## Model Selection

Per **GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md**, tool-capable models (support function calling):

| Model | Size | Speed | Quality | Notes |
|-------|------|-------|---------|-------|
| **qwen2.5:7b-instruct** | 7B | Fast | Excellent | **Recommended** (multilingual, strong tools) |
| llama3.1:8b | 8B | Fast | Very Good | Mature function calling |
| gemma3:4b | 4B | Very Fast | Good | Fallback for ≤4GB VRAM |
| mistral:7b-instruct-v0.3 | 7B | Fast | Very Good | Alternative, weaker tools than qwen |

**Do NOT use:** `mistral`, `llama2` (bare tags without version — lack tool support)

To use a different model:
```bash
ollama pull llama3.1:8b

# Then modify DispatcherAgentModule.kt or pass custom config
```

## VRAM Limits

**Per @bedaHovorka:** Maximum 8GB VRAM for GPU-based inference.

### Docker VRAM Configuration

**Set limit in `.env` file or export before `docker compose up`:**
```bash
# 8GB limit (recommended)
export OLLAMA_MAX_VRAM=8589934592

# 4GB limit (for low-end GPUs)
export OLLAMA_MAX_VRAM=4294967296

# No limit (use all available — not recommended)
export OLLAMA_MAX_VRAM=0
```

**Alternative:** Edit `docker-compose.yml` `ollama` service `environment:` section:
```yaml
environment:
  OLLAMA_MAX_VRAM: 8589934592  # 8GB
```

## Testing

### Unit Tests (Network-Free)

```bash
./gradlew :dispatcher-agent:test
```

Tests: Configuration validation, lazy initialization, resource cleanup.

### Integration Tests with Real Ollama

```bash
# Requires Ollama running at localhost:11434
./gradlew :dispatcher-agent:integrationTest
```

Tests include `@Tag("ollama-test")` proof-of-connection checks:
- **Locally:** Fails if Ollama unreachable (start it first)
- **CI:** Warns and skips if Ollama unavailable (expected in CI)

### Proof-of-Connection Test

```bash
./gradlew :dispatcher-agent:test -i 2>&1 | grep -A5 "local Ollama has"
```

Expected output: Test `local Ollama has the configured tool-capable model pulled` passes.

## Troubleshooting

### "Ollama not reachable at localhost:11434"

**Cause:** Ollama server not running

**Fix:**
- Native: Run `ollama serve` in a terminal
- Docker: Run `docker compose up -d ollama && sleep 10`

### "Model 'qwen2.5:7b-instruct' not pulled"

**Cause:** Model not yet downloaded

**Fix:**
- Native: Run `ollama pull qwen2.5:7b-instruct`
- Docker: Will auto-pull on first startup (patience — ~4.7GB download)

### "Model lacks tool support"

**Cause:** Using a model without function-calling support (e.g., `mistral` or `llama2` bare tags)

**Fix:** Pull a tool-capable model:
```bash
ollama pull qwen2.5:7b-instruct
```

### Connection timeout in Docker

**Cause:** Ollama container too slow to start, or model still pulling

**Fix:** Wait longer before running tests:
```bash
docker compose up -d ollama
sleep 30  # Wait for startup
./gradlew :dispatcher-agent:integrationTest
```

### Out of Memory (OOM) during model load

**Cause:** GPU VRAM too small, or multiple models loading simultaneously

**Fix:**
- Reduce `OLLAMA_MAX_VRAM` (or set `OLLAMA_NUM_PARALLEL=1` to serialize)
- Use smaller model: `gemma3:4b` instead of `qwen2.5:7b-instruct`

## Koin DI Integration

### Accessing the Executor (SP1.6+)

In your Koog agent code:
```kotlin
// Via Koin DI
val executor: OllamaSimpleExecutor by inject()
val koogExecutor = executor.getExecutor()

// Or constructor injection
class MyAgent(private val executor: OllamaSimpleExecutor) {
    suspend fun decide(): DispatchDecision {
        val koogExecutor = executor.getExecutor()
        // Use koogExecutor for LLM calls
    }
}
```

### Shutdown Cleanup (SP1.6+)

When the application shuts down:
```kotlin
val executor: OllamaSimpleExecutor = get()
executor.close()  // Closes Ollama client, idempotent
```

## Performance Tuning

### Inference Time

Typical latencies on qwen2.5:7b-instruct:

| Hardware | Cold (first call) | Warm (cached) |
|----------|-------------------|---------------|
| M1/M2 MacBook (GPU) | 2–3 sec | 0.5–1 sec |
| NVIDIA RTX 4090 | 0.5 sec | 0.1–0.2 sec |
| NVIDIA RTX 3060 (12GB) | 1–2 sec | 0.3–0.5 sec |
| CPU-only (Intel i7) | 15–20 sec | 5–10 sec |

**Optimization tips:**
- Use GPU if available (100x faster than CPU)
- Keep model loaded (don't restart Ollama between runs)
- Reduce `maxTokens` if you don't need long responses (default 1024 is generous)

### Multi-Simulation Concurrency

Since `OllamaSimpleExecutor` is a singleton, all simulations share one Ollama client:
- **Pro:** Reduced memory footprint (one model, not N copies)
- **Con:** LLM calls serialize (only one inference at a time)

For parallel simulations, consider:
- Multiple Ollama instances on different ports (future enhancement)
- Reduced `maxTokens` to speed up inference

## Future Enhancements (SP1.6+)

- [ ] Environment variable override for `OllamaExecutorConfig`
- [ ] LLM call caching (avoid re-inferencing identical observations)
- [ ] Ollama health checks and auto-restart
- [ ] Support for remote Ollama instances (not just localhost)
- [ ] Metrics collection (inference time, token count, cache hits)
- [ ] Graceful fallback to rule-based dispatcher if Ollama unavailable

## References

- **Ollama GitHub:** https://github.com/jmorganca/ollama
- **Ollama API Docs:** https://github.com/jmorganca/ollama/blob/main/docs/api.md
- **Koog Framework:** Internal dependency for Koog agents
- **Goal 10 Context:** docs/GOAL_10_EXPECTATIONS_CRITIQUE.md
- **Model Evaluation:** docs/GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md

## Related Issues

- **SP1.3** (#548): Configuration and connectivity checking
- **SP1.4** (#549): Perception/actuator tool implementations
- **SP1.5** (#550): Ollama executor backend (THIS ISSUE)
- **SP1.6** (#551): Full Koog agent wiring and LLM decision logic
