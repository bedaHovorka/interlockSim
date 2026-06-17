# Design: Remove docker-kdisco Binary Artifacts from interlockSim

**Date:** 2026-06-17  
**Topic:** Replace committed kDisco artifacts with remote GitHub Packages fetch, and add ignore rules to prevent future binary commits.  
**Status:** Proposed (pending user approval)

## 1. Problem Statement

The repository currently contains pre-built kDisco artifacts under `docker-kdisco/` so that Docker builds can seed `mavenLocal()` and resolve kDisco without network access. The latest commit (`a93218b`) added kDisco 0.5.0 `.klib`, `.jar`, `.module`, and `.pom` files there. This violates the project policy of keeping binary artifacts out of the codebase and bloats the repository history.

## 2. Goals

1. Remove the entire `docker-kdisco/` directory from the working tree and Git history where practical.
2. Make Docker builds resolve kDisco from GitHub Packages (Maven repository at `https://maven.pkg.github.com/bedaHovorka/kdisco`) using the existing `GITHUB_ACTOR`/`GITHUB_TOKEN` credentials.
3. Keep a documented manual fallback for local/offline builds if GitHub Packages is unavailable.
4. Prevent future accidental commits of `docker-kdisco/` or similar vendor directories via `.gitignore` and `.dockerignore`.

## 3. Non-Goals

- Re-publishing kDisco artifacts to Maven Central (out of scope; assume GitHub Packages is the canonical source).
- Modifying kDisco itself (separate repo).
- Removing other binary files already tracked for legitimate reasons (images, thesis screenshots, gradle wrapper JAR).

## 4. Proposed Design

### 4.1 Delete the `docker-kdisco/` directory

Remove the whole directory from the repository. This includes all versions currently present (`0.3.0`, `0.4.0`, `0.4.1`, `0.5.0`) and all module variants (`kdisco-core`, `kdisco-core-jvm`, `kdisco-core-js`, `kdisco-core-linuxx64`, `kdisco-koin`, `kdisco-koin-jvm`).

### 4.2 Update `Dockerfile` (JVM editor/sim app)

Remove the following lines:

- Layer 2.5: `COPY docker-kdisco/ /root/kdisco-prebuild/`
- Layer 3: the `cp -rn /root/kdisco-prebuild/. /root/.m2/repository/ && \` line inside the `RUN --mount=...` step.
- Layer 5: the same `cp -rn ...` line inside the build/test `RUN --mount=...` step.

Update the inline comment in Layer 3 to state that kDisco is now fetched directly from GitHub Packages using the `GITHUB_ACTOR`/`GITHUB_TOKEN` build args and cached in the BuildKit `/root/.m2/repository` cache mount.

### 4.3 Update `Dockerfile.fast-sim` (native linuxX64 binary)

Apply the same removals as in `Dockerfile`:

- Remove `COPY docker-kdisco/ /root/kdisco-prebuild/`.
- Remove the two `cp -rn /root/kdisco-prebuild/. /root/.m2/repository/ && \` lines from the dependency-resolution and native-link steps.

The native image still needs kDisco multiplatform artifacts (including `kdisco-core-linuxx64` klib). Gradle will resolve these from GitHub Packages as long as the build args are provided. `Dockerfile.fast-sim` currently does not pass `GITHUB_ACTOR`/`GITHUB_TOKEN`, so add the `ARG` declarations and forward them via `docker-compose.yml`.

### 4.4 Update `docker-compose.yml`

- Add `GITHUB_ACTOR: ${GITHUB_ACTOR}` and `GITHUB_TOKEN: ${GITHUB_TOKEN}` args to the `fast-sim` service build block so it can authenticate to GitHub Packages.
- The `app` service already passes these args; no change needed there.

### 4.5 Update `.gitignore`

Add an entry at the end:

```gitignore
# Pre-built kDisco artifacts for Docker — now fetched from GitHub Packages
/docker-kdisco/
```

Also consider tightening the generic `*.jar` rule to remain in place (it already ignores JARs except `gradle/wrapper/gradle-wrapper.jar`).

### 4.6 Update `.dockerignore`

Replace the explicit comment that says `docker-kdisco/` is intentionally **not** ignored with:

```gitignore
# Pre-built kDisco artifacts — now fetched from GitHub Packages at build time
docker-kdisco/
```

This keeps the directory out of the Docker build context.

### 4.7 Update `CLAUDE.md` / project memory if needed

The `MEMORY.md` currently records that `docker-kdisco/` "contains kDisco 0.4.0 pre-built artifacts for Docker build". That note will become stale and should be updated to state that kDisco is resolved from GitHub Packages, with `docker-kdisco/` reserved only for optional local/offline fallback if a user creates it manually.

### 4.8 Local/offline fallback (option C, not committed)

If GitHub Packages is unavailable during a build, a developer can still:

1. Check out the kDisco repo locally.
2. Run `./gradlew :kdisco-core:publishToMavenLocal :kdisco-koin:publishToMavenLocal`.
3. Temporarily create a local `docker-kdisco/` directory copied from `~/.m2/repository` and mount it into the builder using a Docker bind mount or build context override.

This fallback is manual and must not be checked in.

## 5. Verification Plan

1. After removing the directory and updating files, run `git rm -r docker-kdisco/` and commit.
2. Validate that `docker compose build app` succeeds with valid `GITHUB_ACTOR`/`GITHUB_TOKEN` exported.
3. Validate that `docker compose build fast-sim` succeeds (after adding the GitHub args).
4. Run `./gradlew clean build` locally (non-Docker) to confirm no Gradle changes break the host build.
5. Check that `.gitignore` and `.dockerignore` take effect:
   - `git status` no longer lists `docker-kdisco/` as untracked after deletion.
   - `docker buildx build . --no-cache -t test-context` shows a smaller build context.

## 6. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| GitHub Packages unavailable during CI or local build | Use the local publish-to-mavenLocal fallback described in 4.8. Document it in `CLAUDE.md` and `MEMORY.md`. |
| `fast-sim` image lacks GitHub credentials | Add `GITHUB_ACTOR`/`GITHUB_TOKEN` args to `Dockerfile.fast-sim` and `docker-compose.yml`. |
| BuildKit cache mount loses kDisco artifacts between builds | Network fetch is required only when the cache mount is cold; this is acceptable because credentials are already required. |
| `.gitignore` entry not retroactive for history | Stop tracking the directory going forward; repository history remains unless rewritten (out of scope). |

## 7. Success Criteria

- `docker-kdisco/` is no longer tracked by Git and is ignored by both Git and Docker.
- Both Docker images (`app` and `fast-sim`) build successfully with credentials.
- No binary kDisco artifacts remain in the repository working tree.
- Future accidental creation of `docker-kdisco/` is blocked by ignore rules.
