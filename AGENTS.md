# Repository Guidelines

## Project Structure & Module Organization

This is a Java 8 multi-module Maven learning repository. Root `pom.xml` aggregates `base`, `jdk`, `jvm`, `nio`, `netty`, `concurrency`, and `redis`. Java code lives in each module under `src/main/java`; tests live under `src/test/java`. Shared package names follow `yier.bubu.<module>`.

Documentation is maintained with MkDocs in `mkdocs/`. Module docs remain in paths such as `concurrency/docs/`, `jvm/docs/`, and `redis/docs/`, then are linked into `mkdocs/docs/`. Reference material is stored under `references/`.

## Build, Test, and Development Commands

Run commands from the repository root.

- `mvn test`: compile all Maven modules and run JUnit tests through Surefire.
- `mvn -pl concurrency test`: run tests for one module; replace `concurrency` with another module name as needed.
- `python3 -m pip install -r mkdocs/requirements.txt`: install MkDocs tooling.
- `mkdocs build -f mkdocs/mkdocs.yml`: build the documentation site into `mkdocs/site/`.
- `mkdocs serve -f mkdocs/mkdocs.yml`: preview documentation locally.
- `mkdocs/scripts/verify_math.sh`, `mkdocs/scripts/verify_layout_width.sh`, and `mkdocs/scripts/verify_diagrams.sh`: validate generated documentation behavior.

## Coding Style & Naming Conventions

Use UTF-8 and Java 8-compatible APIs. Follow the existing Java style: 4-space indentation, braces on the same line, `PascalCase` classes, `camelCase` methods and fields, and uppercase enum constants. Keep examples small and focused; this repo favors readable learning code over framework-heavy abstractions.

No formatter or linter is configured, so match nearby code. Markdown docs should use clear headings, fenced code blocks, and relative links. Numbered Markdown headings should use numeric dot form such as `## 1. Topic`, not parenthesized form such as `## 1) Topic`.

## Testing Guidelines

Tests use JUnit 4 (`org.junit.Test`, `Assert`) and Maven Surefire. Name test classes with `*Test` when they should run in normal builds. Existing test methods often use `method_shouldExpectedBehavior` naming; keep that pattern for new coverage.

Concurrency experiments may use JCStress annotations under `concurrency/src/test/java`; document expected outcomes carefully and avoid treating probabilistic races as deterministic unit tests.

## Commit & Pull Request Guidelines

Recent commits mostly use short Conventional Commit-style prefixes such as `docs:`, `feat:`, and `chore:`, with imperative summaries. Keep commits focused on one module or doc area.

Pull requests should describe the change, list affected modules or docs paths, link related issues when available, and include the commands run. For MkDocs layout or visual changes, include a rendered-page screenshot or note the relevant verification script.

## Agent-Specific Instructions

Do not overwrite generated or local workspace changes you did not make. Prefer updating source docs and scripts over editing generated files in `mkdocs/site/` directly.
Do not modify content under `references/` unless the user explicitly requests it.
