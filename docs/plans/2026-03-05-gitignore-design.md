# Git Ignore Cleanup Design

**Status:** Draft

**Goal:** Keep repository working trees clean by ignoring build output, IDE metadata, OS junk files, and local tooling directories—without hiding source artifacts.

---

## Context

This repository contains multiple Java/Maven modules and a repository-level MkDocs site. We want `.gitignore` to:

- Prevent accidental commits of generated output (e.g., `target/`, logs).
- Reduce noise from IDE/editor metadata (IntelliJ, VS Code, Eclipse).
- Keep agent/tool scratch directories out of version control.

## Design Principles

- Prefer ignoring **directories** for generated outputs (stable and low-maintenance).
- Keep rules **sectioned and documented** by tool/type.
- Avoid ignoring source-like extensions broadly unless they are **known build artifacts**.
- Keep MkDocs build output under `target/mkdocs` so it is covered by the existing `target/` ignore rule.

## Proposed Organization (Root `.gitignore`)

- Java / Maven: `target/` and Maven release backups
- Java artifacts: `*.class`, archives, crash logs
- IDEs: IntelliJ / Eclipse / VS Code
- Editors: swap/backup files
- OS: `.DS_Store`, `Thumbs.db`
- Tools: local agent directories (e.g., `.codex`, `.serena/`, `.worktrees/`)

## Risks / Tradeoffs

- **Over-ignoring** could hide useful diagnostics or local scripts.
  - Mitigation: keep ignores scoped to well-known generated outputs and tool metadata.

## Rollout

1. Apply `.gitignore` cleanup in a single change.
2. Verify with `git status` that generated output is not staged/tracked.
3. Run `mkdocs build` and ensure output remains under ignored paths (`target/mkdocs`).

