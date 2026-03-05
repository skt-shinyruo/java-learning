# Git Ignore Cleanup Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a repository-wide `.gitignore` for Maven + IDE outputs, then remove already-tracked `**/target/**` build artifacts from the Git index.

**Architecture:** Use one root `.gitignore` so ignore rules apply to all Maven modules; use `git rm --cached` to untrack existing build outputs while keeping local build files on disk.

**Tech Stack:** Git, Maven (multi-module), IntelliJ IDEA.

---

### Task 1: Create repository `.gitignore`

**Files:**
- Create: `.gitignore`

**Step 1: Add `.gitignore`**

Create `.gitignore` with:

```gitignore
# Java / Maven
target/
*.class
*.log

# IntelliJ IDEA
.idea/
*.iml
*.ipr
*.iws
out/

# Eclipse
.classpath
.project
.settings/

# VS Code
.vscode/

# OS / misc
.DS_Store
Thumbs.db
```

**Step 2: Verify ignore is active**

Run: `git status -sb`  
Expected: `.idea/` no longer appears as untracked (it’s ignored).

---

### Task 2: Untrack existing Maven build outputs

**Files:**
- Modify: Git index (no source file changes)

**Step 1: Remove tracked `target/` trees from Git index**

Run:

```bash
git rm -r --cached base/target jdk/target netty/target concurrency/target
```

Expected: Git reports removed files; local files remain on disk.

**Step 2: Verify staged changes**

Run: `git status -sb`  
Expected: `**/target/**` shows as deleted (staged), and no longer shows as modified files.

---

### Task 3: Final verification

**Step 1: Ensure workspace is clean of build/IDE noise**

Run: `git status -sb`  
Expected: Only meaningful source changes remain (if any), and `target/` / `.idea/` do not appear.

**Step 2 (Optional): Build sanity check**

Run: `mvn -q test`  
Expected: build + tests succeed.