# Fix fast-sim libxml2.so.16 SONAME Mismatch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the `fast-sim` Docker image runtime error `libxml2.so.16: No such file or directory` by ensuring the builder and runtime stages use compatible libxml2 SONAMEs.

**Architecture:** Pin the multi-stage Dockerfile's builder to a stable LTS base (`eclipse-temurin:21-jdk-noble`, Ubuntu 24.04 LTS) whose libxml2 provides `libxml2.so.2`, matching the Alpine 3.21 runtime. Update stale comments that incorrectly describe the builder as Debian Bookworm.

**Tech Stack:** Docker, Docker Compose, Kotlin/Native, Gradle, Alpine Linux, Eclipse Temurin

## Global Constraints

- Must not change functional behavior of the `fast-sim` binary.
- Must keep the runtime image on `alpine:3.21` (project requirement for small image size).
- Builder and runtime libxml2 SONAMEs must match.
- Changes are limited to `Dockerfile.fast-sim` and the related specification document.
- Verification requires `docker compose build fast-sim` and `docker compose run fast-sim example shuntingLoop 60`.

---

## File Structure

- **`Dockerfile.fast-sim:24`** — Builder base image. Currently uses the rolling tag `eclipse-temurin:21-jdk`, which now resolves to Ubuntu 26.04 (Resolute) with `libxml2.so.16`. This is the single line that must change.
- **`Dockerfile.fast-sim:20-24` and `Dockerfile.fast-sim:92-99`** — Stale comments describing the builder as "Debian Bookworm". Must be rewritten to describe Ubuntu 24.04 LTS (Noble) and the SONAME compatibility rationale.
- **`docs/superpowers/specs/2026-03-21-fast-sim-design.md:196-204`** — Specification snippet that shows the old builder image in a code block and claims the runtime base is Alpine 3.21 with `gcompat`. The builder reference must be updated to match the pinned tag.
- **`docker-compose.yml`** — Already forwards `GITHUB_ACTOR` and `GITHUB_TOKEN` to the `fast-sim` build. No changes required.

---

### Task 1: Pin fast-sim builder image to noble LTS

**Files:**
- Modify: `Dockerfile.fast-sim:24`
- Test: `docker compose build fast-sim` and `docker compose run fast-sim example shuntingLoop 60`

**Interfaces:**
- Consumes: None (build-configuration change only).
- Produces: A `Dockerfile.fast-sim` whose builder stage links against `libxml2.so.2`, producing a native binary compatible with the Alpine 3.21 runtime.

- [x] **Step 1: Read the current builder stage**

Read `Dockerfile.fast-sim` lines 20-30 to confirm the current base image line:

```dockerfile
FROM --platform=linux/amd64 eclipse-temurin:21-jdk AS builder
```

- [x] **Step 2: Replace the rolling tag with the noble LTS tag**

Change line 24 in `Dockerfile.fast-sim` from:

```dockerfile
FROM --platform=linux/amd64 eclipse-temurin:21-jdk AS builder
```

To:

```dockerfile
FROM --platform=linux/amd64 eclipse-temurin:21-jdk-noble AS builder
```

- [x] **Step 3: Build the fast-sim image**

Run:

```bash
docker compose build fast-sim
```

Expected: Build completes successfully and produces image `interlocksim-fast-sim:latest`. The build log must not contain errors about missing packages or failed native linking.

- [x] **Step 4: Run the example to verify the runtime SONAME error is gone**

Run:

```bash
docker compose run fast-sim example shuntingLoop 60
```

Expected: The container starts without the error `Error loading shared library libxml2.so.16: No such file or directory`. The simulation should proceed and produce normal text output.

- [x] **Step 5: Commit the change**

```bash
git add Dockerfile.fast-sim
git commit -m "build(docker): pin fast-sim builder to eclipse-temurin:21-jdk-noble

The rolling eclipse-temurin:21-jdk tag now resolves to Ubuntu 26.04
(Resolute), whose libxml2 package provides libxml2.so.16. The runtime
image uses alpine:3.21, which only provides libxml2.so.2. Pin the
builder to Ubuntu 24.04 LTS (Noble) so the linked SONAME matches the
runtime and the binary starts successfully.

Fixes runtime error: libxml2.so.16: No such file or directory

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: Update stale Dockerfile.fast-sim comments

**Files:**
- Modify: `Dockerfile.fast-sim:20-24`
- Modify: `Dockerfile.fast-sim:92-99`
- Modify: `docs/superpowers/specs/2026-03-21-fast-sim-design.md:196-204`

**Interfaces:**
- Consumes: The pinned builder image from Task 1 (`eclipse-temurin:21-jdk-noble`).
- Produces: Accurate comments and specification documentation that describe Ubuntu 24.04 LTS (Noble) as the builder base.

- [x] **Step 1: Read the stale comments**

Read `Dockerfile.fast-sim` lines 18-40 and 88-105. Note the comments that refer to "Debian Bookworm" and the original rationale.

Current lines 20-24:

```dockerfile
# Eclipse Temurin provides JDK 21 on Debian Bookworm base.
# Debian bookworm only ships openjdk-17, but Gradle + Kotlin require JDK 21.
FROM --platform=linux/amd64 eclipse-temurin:21-jdk AS builder
```

Current lines 92-99:

```dockerfile
# Alpine Linux base (~8MB) instead of debian:bookworm-slim (base ~85MB, total 174MB with libxml2+libicu).
# Fixes #421: image exceeded 120MB target because Debian's libxml2
# depends on libicu72 (~30MB). Alpine's libxml2 has no ICU dependency.
FROM --platform=linux/amd64 alpine:3.21
```

- [x] **Step 2: Rewrite the builder stage comments in the Dockerfile**

Replace lines 20-24 with:

```dockerfile
# Eclipse Temurin provides JDK 21 on Ubuntu 24.04 LTS (Noble).
# We pin to the -noble tag instead of the rolling 21-jdk tag because the
# rolling tag now resolves to Ubuntu 26.04 (Resolute), whose libxml2 has
# SONAME libxml2.so.16 — incompatible with alpine:3.21's libxml2.so.2.
FROM --platform=linux/amd64 eclipse-temurin:21-jdk-noble AS builder
```

- [x] **Step 3: Clarify the runtime stage comments**

Replace lines 92-99 with:

```dockerfile
# Alpine Linux base (~8MB) instead of Ubuntu/Debian slim (~85MB+ with libxml2+libicu).
# Fixes #421: image exceeded 120MB target because Debian/Ubuntu's libxml2
# depends on libicu (~30MB). Alpine's libxml2 has no ICU dependency.
# The builder is pinned to Ubuntu 24.04 LTS (Noble) so its libxml2.so.2
# SONAME matches Alpine 3.21's libxml2 at runtime.
FROM --platform=linux/amd64 alpine:3.21
```

- [x] **Step 4: Update the specification document code block**

Read `docs/superpowers/specs/2026-03-21-fast-sim-design.md` lines 194-208. Replace the code block with:

```dockerfile
# Build stage (Eclipse Temurin JDK 21 on Ubuntu 24.04 LTS Noble + libxml2-dev for cinterop)
FROM --platform=linux/amd64 eclipse-temurin:21-jdk-noble AS builder
RUN apt-get update && apt-get install -y --no-install-recommends git libxml2-dev libicu-dev
COPY . /build
WORKDIR /build
RUN ./gradlew :fast-sim:linkReleaseExecutableLinuxX64

# Runtime stage (Alpine — no ICU dependency, ~20MB total)
FROM --platform=linux/amd64 alpine:3.21
RUN apk add --no-cache gcompat libxml2
COPY --from=builder /build/fast-sim/build/bin/linuxX64/releaseExecutable/fast-sim.kexe /usr/local/bin/fast-sim
ENTRYPOINT ["fast-sim"]
```

Also update the bullet directly below the code block from:

```markdown
- Runtime base is `alpine:3.21` (~8MB) with `gcompat` for glibc ABI compatibility
- Alpine's `libxml2` does NOT depend on `libicu` (unlike Debian's ~30MB transitive dependency)
```

To:

```markdown
- Runtime base is `alpine:3.21` (~8MB) with `gcompat` for glibc ABI compatibility
- Alpine's `libxml2` does NOT depend on `libicu` (unlike Debian/Ubuntu's ~30MB transitive dependency)
- Builder is pinned to `eclipse-temurin:21-jdk-noble` (Ubuntu 24.04 LTS) because its `libxml2.so.2` SONAME matches Alpine 3.21
```

- [x] **Step 5: Commit the comment and documentation updates**

```bash
git add Dockerfile.fast-sim docs/superpowers/specs/2026-03-21-fast-sim-design.md
git commit -m "docs(docker): update fast-sim builder base image comments

Replace outdated references to Debian Bookworm with Ubuntu 24.04 LTS
(Noble) and document the libxml2 SONAME compatibility requirement that
motivates pinning the builder image.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: Verify fast-sim Docker example run end-to-end

**Files:**
- No file changes (pure verification task).

**Interfaces:**
- Consumes: The fixed `Dockerfile.fast-sim` from Task 1 and Task 2.
- Produces: Confirmation that the `fast-sim` container can run the built-in `shuntingLoop` example to completion without the libxml2 SONAME error.

- [x] **Step 1: Ensure the fixed image is built**

Run:

```bash
docker compose build fast-sim
```

Expected: Build completes with no errors.

- [x] **Step 2: Run the built-in example**

Run:

```bash
docker compose run fast-sim example shuntingLoop 60
```

Expected: The container starts, the native binary runs, and the output shows the shunting loop simulation completing. There must be no `libxml2.so.16: No such file or directory` message.

Result: `--- Simulation complete: 2 trains, 60.9s sim time, 0.0s wall ---`

- [x] **Step 3: Confirm no regression in `--help` mode**

Run:

```bash
docker compose run fast-sim --help
```

Expected: Help text is printed and the container exits cleanly.

- [x] **Step 4: Record verification result**

No file changes are required. Simulation completed successfully: `--- Simulation complete: 2 trains, 60.9s sim time, 0.0s wall ---`. No libxml2 SONAME error observed.

---

## Self-Review

**1. Spec coverage:**
- Fix runtime `libxml2.so.16` error → Task 1 (image pin) and Task 3 (verification).
- Pin builder to stable LTS → Task 1.
- Update stale "Debian Bookworm" comments → Task 2.
- Verify with `docker compose run fast-sim example shuntingLoop 60` → Task 3.
- No functional behavior changes → enforced by limiting edits to base image and comments.

**2. Placeholder scan:**
- No "TBD", "TODO", "implement later", or vague instructions remain.
- Every code block contains literal text to add/replace.
- Exact commands and expected outputs are provided for every verification step.

**3. Type consistency:**
- The pinned image name `eclipse-temurin:21-jdk-noble` is identical in `Dockerfile.fast-sim` and `docs/superpowers/specs/2026-03-21-fast-sim-design.md`.
- The runtime image remains `alpine:3.21` everywhere.
- The builder comments consistently refer to Ubuntu 24.04 LTS (Noble) and the SONAME compatibility issue.
