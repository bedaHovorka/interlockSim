# Remove docker-kdisco Binary Artifacts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the committed `docker-kdisco/` binary artifact directory and make Docker builds resolve kDisco from GitHub Packages instead.

**Architecture:** Remove the vendored Maven-local seed directory, delete the `COPY`/`cp` steps that injected it into Docker builder `mavenLocal()`, add GitHub Packages auth to the native `fast-sim` Dockerfile, and add `.gitignore`/`.dockerignore` rules so the directory cannot re-enter the repository or build context.

**Tech Stack:** Docker, Docker Compose, Gradle (Kotlin DSL), Git, GitHub Packages Maven repository.

---

### Task 1: Delete the `docker-kdisco/` directory from Git

**Files:**
- Delete (git): `docker-kdisco/`

- [ ] **Step 1: Remove the tracked directory**

```bash
git rm -r docker-kdisco/
```

Expected: Git reports deletion of all files under `docker-kdisco/`. No `*.klib`, `*.jar`, `*.module`, or `*.pom` files remain tracked.

- [ ] **Step 2: Verify nothing is left**

```bash
git ls-files | grep -E "^docker-kdisco/"
```

Expected: empty output.

- [ ] **Step 3: Commit the deletion**

```bash
git commit -m "chore: remove committed docker-kdisco binary artifacts

Delete pre-built kDisco artifacts from the repository. These will now
be fetched from GitHub Packages at Docker build time.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: Update `Dockerfile` to fetch kDisco from GitHub Packages

**Files:**
- Modify: `Dockerfile`

- [ ] **Step 1: Remove the pre-stage layer comment and COPY**

Replace lines 55-61 (Layer 2.5):

```dockerfile
# Layer 2.5: Pre-stage kdisco-core-jvm:0.4.0 artifacts
# Copied to /root/kdisco-prebuild/ (not /root/.m2/) because the build-step
# cache mount at /root/.m2/repository would shadow a direct COPY there.
# The RUN step below installs from this location into the mounted mavenLocal.
# To refresh: re-run `./gradlew :kdisco-core:publishToMavenLocal` in kdisco
# and copy the output to docker-kdisco/ in this repo.
COPY docker-kdisco/ /root/kdisco-prebuild/
```

with:

```dockerfile
# Layer 2.5: kDisco is fetched from GitHub Packages during dependency resolution.
# Provide GITHUB_ACTOR and GITHUB_TOKEN build args (already declared above).
# BuildKit cache mount at /root/.m2/repository keeps the downloaded artifacts warm.
```

- [ ] **Step 2: Remove the prebuild copy from dependency resolution**

Replace the Layer 3 `RUN` block (lines 65-70):

```dockerfile
RUN --mount=type=cache,target=/root/.gradle/caches,id=app-gradle \
    --mount=type=cache,target=/root/.gradle/wrapper,id=app-wrapper \
    --mount=type=cache,target=/root/.m2/repository,id=app-m2 \
    cp -rn /root/kdisco-prebuild/. /root/.m2/repository/ && \
    GITHUB_ACTOR=${GITHUB_ACTOR} GITHUB_TOKEN=${GITHUB_TOKEN} \
    ./gradlew dependencies --no-daemon --warning-mode=summary
```

with:

```dockerfile
RUN --mount=type=cache,target=/root/.gradle/caches,id=app-gradle \
    --mount=type=cache,target=/root/.gradle/wrapper,id=app-wrapper \
    --mount=type=cache,target=/root/.m2/repository,id=app-m2 \
    GITHUB_ACTOR=${GITHUB_ACTOR} GITHUB_TOKEN=${GITHUB_TOKEN} \
    ./gradlew dependencies --no-daemon --warning-mode=summary
```

- [ ] **Step 3: Remove the prebuild copy from the build/test step**

Replace the Layer 5 `RUN` block (lines 80-85):

```dockerfile
RUN --mount=type=cache,target=/root/.gradle/caches,id=app-gradle \
    --mount=type=cache,target=/root/.gradle/wrapper,id=app-wrapper \
    --mount=type=cache,target=/root/.m2/repository,id=app-m2 \
    cp -rn /root/kdisco-prebuild/. /root/.m2/repository/ && \
    GITHUB_ACTOR=${GITHUB_ACTOR} GITHUB_TOKEN=${GITHUB_TOKEN} \
    ./gradlew clean build shadowJar --no-daemon --warning-mode=summary
```

with:

```dockerfile
RUN --mount=type=cache,target=/root/.gradle/caches,id=app-gradle \
    --mount=type=cache,target=/root/.gradle/wrapper,id=app-wrapper \
    --mount=type=cache,target=/root/.m2/repository,id=app-m2 \
    GITHUB_ACTOR=${GITHUB_ACTOR} GITHUB_TOKEN=${GITHUB_TOKEN} \
    ./gradlew clean build shadowJar --no-daemon --warning-mode=summary
```

- [ ] **Step 4: Verify the Dockerfile still has ARG declarations**

Read `Dockerfile` lines 25-28 and confirm they still contain:

```dockerfile
ARG GITHUB_ACTOR
ARG GITHUB_TOKEN
```

If they are missing, add them immediately after the `FROM ... AS builder` line.

- [ ] **Step 5: Commit**

```bash
git add Dockerfile
git commit -m "build(docker): fetch kDisco from GitHub Packages in app image

Remove docker-kdisco/ seeding from Dockerfile. kDisco is now resolved
from GitHub Packages using the existing GITHUB_ACTOR/GITHUB_TOKEN args.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: Update `Dockerfile.fast-sim` for remote kDisco fetch

**Files:**
- Modify: `Dockerfile.fast-sim`

- [ ] **Step 1: Add GitHub Packages auth ARGs**

After line 23 (`FROM --platform=linux/amd64 eclipse-temurin:21-jdk AS builder`), add:

```dockerfile
# Build arguments for GitHub Packages authentication (required for kDisco)
ARG GITHUB_ACTOR
ARG GITHUB_TOKEN
```

- [ ] **Step 2: Remove the pre-stage layer and COPY**

Replace lines 56-57:

```dockerfile
# Layer 2.5: Pre-stage kDisco artifacts for offline build
COPY docker-kdisco/ /root/kdisco-prebuild/
```

with:

```dockerfile
# Layer 2.5: kDisco artifacts are fetched from GitHub Packages during resolution.
# GITHUB_ACTOR and GITHUB_TOKEN are forwarded from docker-compose.yml.
```

- [ ] **Step 3: Remove the prebuild copy from dependency resolution**

Replace the Layer 3 `RUN` block (lines 60-64):

```dockerfile
RUN --mount=type=cache,target=/root/.gradle/caches,id=fast-sim-gradle \
    --mount=type=cache,target=/root/.gradle/wrapper,id=fast-sim-wrapper \
    --mount=type=cache,target=/root/.m2/repository,id=fast-sim-m2 \
    cp -rn /root/kdisco-prebuild/. /root/.m2/repository/ && \
    ./gradlew dependencies --no-daemon --warning-mode=summary
```

with:

```dockerfile
RUN --mount=type=cache,target=/root/.gradle/caches,id=fast-sim-gradle \
    --mount=type=cache,target=/root/.gradle/wrapper,id=fast-sim-wrapper \
    --mount=type=cache,target=/root/.m2/repository,id=fast-sim-m2 \
    GITHUB_ACTOR=${GITHUB_ACTOR} GITHUB_TOKEN=${GITHUB_TOKEN} \
    ./gradlew dependencies --no-daemon --warning-mode=summary
```

- [ ] **Step 4: Remove the prebuild copy from the native link step**

Replace the Layer 5 `RUN` block (lines 76-80):

```dockerfile
RUN --mount=type=cache,target=/root/.gradle/caches,id=fast-sim-gradle \
    --mount=type=cache,target=/root/.gradle/wrapper,id=fast-sim-wrapper \
    --mount=type=cache,target=/root/.m2/repository,id=fast-sim-m2 \
    cp -rn /root/kdisco-prebuild/. /root/.m2/repository/ && \
    ./gradlew :fast-sim:linkReleaseExecutableLinuxX64 --no-daemon --warning-mode=summary
```

with:

```dockerfile
RUN --mount=type=cache,target=/root/.gradle/caches,id=fast-sim-gradle \
    --mount=type=cache,target=/root/.gradle/wrapper,id=fast-sim-wrapper \
    --mount=type=cache,target=/root/.m2/repository,id=fast-sim-m2 \
    GITHUB_ACTOR=${GITHUB_ACTOR} GITHUB_TOKEN=${GITHUB_TOKEN} \
    ./gradlew :fast-sim:linkReleaseExecutableLinuxX64 --no-daemon --warning-mode=summary
```

- [ ] **Step 5: Commit**

```bash
git add Dockerfile.fast-sim
git commit -m "build(docker): fetch kDisco from GitHub Packages in fast-sim image

Remove docker-kdisco/ seeding and add GITHUB_ACTOR/GITHUB_TOKEN args so
the native fast-sim image can resolve kDisco from GitHub Packages.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: Forward GitHub credentials to the `fast-sim` service

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Add build args to the `fast-sim` service**

Replace the `fast-sim` build block (lines 74-80):

```yaml
  fast-sim:
    build:
      context: .
      dockerfile: Dockerfile.fast-sim
      args:
        BUILDKIT_INLINE_CACHE: 1
```

with:

```yaml
  fast-sim:
    build:
      context: .
      dockerfile: Dockerfile.fast-sim
      args:
        GITHUB_ACTOR: ${GITHUB_ACTOR}
        GITHUB_TOKEN: ${GITHUB_TOKEN}
        BUILDKIT_INLINE_CACHE: 1
```

- [ ] **Step 2: Commit**

```bash
git add docker-compose.yml
git commit -m "build(compose): pass GitHub credentials to fast-sim build

Required so fast-sim can authenticate to GitHub Packages and download kDisco.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: Add `.gitignore` rule for `docker-kdisco/`

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: Append the ignore rule**

Add at the end of `.gitignore`:

```gitignore

# Pre-built kDisco artifacts for Docker — now fetched from GitHub Packages
/docker-kdisco/
```

- [ ] **Step 2: Verify `git status` ignores any residual directory**

If a local `docker-kdisco/` directory still exists (untracked), run:

```bash
git status --short | grep docker-kdisco
```

Expected: empty output.

- [ ] **Step 3: Commit**

```bash
git add .gitignore
git commit -m "chore(gitignore): ignore docker-kdisco/ vendor directory

Prevent accidental re-commit of pre-built kDisco artifacts.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: Add `.dockerignore` rule for `docker-kdisco/`

**Files:**
- Modify: `.dockerignore`

- [ ] **Step 1: Replace the outdated comment and add the ignore rule**

Find the block (lines 9-15):

```gitignore
# Dependency caches - managed via BuildKit cache mounts
# docker-kdisco/ is intentionally NOT listed here — it contains pre-built kdisco artifacts for the Docker build
.ivy2/
.m2/
jdisco/.m2/
jdisco/target/
```

Replace it with:

```gitignore
# Dependency caches - managed via BuildKit cache mounts
# kDisco is now fetched from GitHub Packages at build time
docker-kdisco/
.ivy2/
.m2/
jdisco/.m2/
jdisco/target/
```

- [ ] **Step 2: Verify the build context shrinks**

```bash
docker buildx build . --no-cache -t test-context 2>&1 | head -n 5
```

Expected: build proceeds without sending `docker-kdisco/` files in the context. (This command only checks context parsing; it may fail later if credentials are missing, which is acceptable for this step.)

- [ ] **Step 3: Commit**

```bash
git add .dockerignore
git commit -m "chore(dockerignore): exclude docker-kdisco/ from build context

kDisco is now fetched from GitHub Packages, so the vendored directory
must not be sent to the Docker daemon.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: Update stale memory note about `docker-kdisco/`

**Files:**
- Modify: `/home/beda/.claude/projects/-home-beda-work-interlockSim/memory/MEMORY.md`

- [ ] **Step 1: Update the project memory entry**

Open `/home/beda/.claude/projects/-home-beda-work-interlockSim/memory/MEMORY.md` and locate the line (currently around line 67) that reads:

```markdown
- **docker-kdisco/**: contains kdisco 0.4.0 pre-built artifacts for Docker build (must match `kdiscoVersion` in interlockSim `gradle.properties`)
```

Replace it with:

```markdown
- **docker-kdisco/**: no longer tracked. kDisco is fetched from GitHub Packages at Docker build time. A local `docker-kdisco/` directory may be used as a manual offline fallback but must never be committed.
```

If the exact line does not exist, search for any mention of `docker-kdisco` in the memory directory and update each occurrence.

**Note:** This file is outside the interlockSim git worktree. It must be edited directly with the `Write`/`Edit` tool; do not attempt to commit it via the interlockSim repository.

- [ ] **Step 2: Confirm the update**

Re-read the edited line in `MEMORY.md` and verify it no longer describes `docker-kdisco/` as a committed source of artifacts.

---

### Task 8: Verify the builds still work

**Files:**
- No file changes — pure verification.

- [ ] **Step 1: Export GitHub Packages credentials**

```bash
export GITHUB_ACTOR=your-github-username
export GITHUB_TOKEN=your-personal-access-token
```

- [ ] **Step 2: Build the `app` image**

```bash
docker compose build app
```

Expected: succeeds and produces `interlocksim:latest`. No mention of `docker-kdisco/` in the build output.

- [ ] **Step 3: Build the `fast-sim` image**

```bash
docker compose build fast-sim
```

Expected: succeeds and produces `interlocksim-fast-sim:latest`.

- [ ] **Step 4: Run the local Gradle build (non-Docker) to confirm no regressions**

```bash
./gradlew clean build
```

Expected: all unit and integration tests pass (this may take ~1 minute).

- [ ] **Step 5: Confirm no binaries were reintroduced**

```bash
git status --short
git ls-files | grep -E "^docker-kdisco/"
```

Expected: `git status` shows only the planned modified files (if any remaining uncommitted), and `git ls-files` returns empty.

- [ ] **Step 6: Final commit or summary**

If any verification fixes were required, commit them. Otherwise, the task list is complete.

---

## Self-Review Checklist

**1. Spec coverage:**
- Delete `docker-kdisco/` → Task 1.
- Update `Dockerfile` → Task 2.
- Update `Dockerfile.fast-sim` and add args → Task 3.
- Update `docker-compose.yml` to forward credentials → Task 4.
- `.gitignore` rule → Task 5.
- `.dockerignore` rule → Task 6.
- Update memory/CLAUDE.md note → Task 7.
- Verification builds → Task 8.
- Local/offline fallback → documented in Task 7 memory note and not committed.

**2. Placeholder scan:**
- No "TBD", "TODO", "implement later", or vague instructions.
- All file paths are exact.
- All code blocks contain the literal before/after text or commands.

**3. Type consistency:**
- `GITHUB_ACTOR`/`GITHUB_TOKEN` names match across `Dockerfile`, `Dockerfile.fast-sim`, and `docker-compose.yml`.
- Cache mount IDs (`app-gradle`, `app-wrapper`, `app-m2`, `fast-sim-gradle`, `fast-sim-wrapper`, `fast-sim-m2`) remain unchanged.
