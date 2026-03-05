# .gitignore for `java-learning` (Design)

**Goal:** Prevent build/IDE artifacts from being committed to Git, and clean already-tracked Maven build outputs (e.g. `**/target/**`) from the repository index.

## Context

- Repository: `E:\\code\\java\\java-learning`
- Build: multi-module Maven project (`packaging=pom`) with modules:
  - `base`
  - `jdk`
  - `netty`
  - `concurrency`
- Current Git state shows compiled outputs under `**/target/**` are tracked and changing (e.g. `*.class` files), and IntelliJ project files under `.idea/` are present locally.

## Requirements

1. Ignore Maven build output across all modules (`target/` directories).
2. Ignore IntelliJ IDEA metadata (`.idea/`, `*.iml`, etc.).
3. Keep source code and `pom.xml` files tracked.
4. Remove already-tracked build artifacts from the Git index so they stop appearing in `git status` and won’t be committed again.

## Approach

1. Add a single repository-root `.gitignore` covering:
   - Maven build outputs (`target/`, `*.class`)
   - IntelliJ IDEA files (`.idea/`, `*.iml`, etc.)
   - Common IDE / OS noise (Eclipse, VS Code, `.DS_Store`, `Thumbs.db`)
2. Clean existing tracked build artifacts without deleting local files by running:
   - `git rm -r --cached base/target jdk/target netty/target concurrency/target`
3. Verify:
   - `git status -sb` no longer reports changes under `**/target/**`
   - `.idea/` is ignored (no longer shows as untracked)