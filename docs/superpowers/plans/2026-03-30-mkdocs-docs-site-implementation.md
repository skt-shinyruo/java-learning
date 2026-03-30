# MkDocs Aggregated Docs Site Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a repository-wide MkDocs site that aggregates the existing root and module documentation into one browser-readable entry point without moving the source documents.

**Architecture:** Add a repository-level `mkdocs.yml` that points `docs_dir` at a new `docs-site` aggregation layer and writes generated output to `target/mkdocs`. Keep the actual Markdown, HTML, PDF, and image assets in their current source directories by exposing each existing docs tree through relative symbolic links under `docs-site`, then use small hand-written landing pages plus explicit navigation entries to present the repository clearly.

**Tech Stack:** MkDocs 1.x, Markdown, YAML, Git symbolic links, existing repository `.gitignore`

---

## File Structure

- Create: `mkdocs.yml`
  Responsibility: define the site name, strict build behavior, output directory, and explicit navigation across all aggregated documentation.
- Create: `docs-site/index.md`
  Responsibility: act as the home page that explains the site sections and reading model.
- Create: `docs-site/concurrency/index.md`
  Responsibility: introduce the concurrency notes and point readers to the recommended reading order plus JMM reference material.
- Create: `docs-site/redis/index.md`
  Responsibility: introduce the Redis notes and distinguish them from repository process docs.
- Create: `docs-site/jdk/index.md`
  Responsibility: introduce the JDK note section.
- Create: `docs-site/jvm/index.md`
  Responsibility: introduce the JVM note section.
- Create: `docs-site/project-docs/index.md`
  Responsibility: introduce the root `docs` tree as repository design and implementation material.
- Create: `docs-site/concurrency/content`
  Responsibility: symbolic link to `concurrency/docs` so MkDocs can render topic pages next to existing local assets.
- Create: `docs-site/redis/content`
  Responsibility: symbolic link to `redis/docs`.
- Create: `docs-site/jdk/content`
  Responsibility: symbolic link to `jdk/docs`.
- Create: `docs-site/jvm/content`
  Responsibility: symbolic link to `jvm/docs`.
- Create: `docs-site/project-docs/content`
  Responsibility: symbolic link to the root `docs` tree.

## Preflight

- Keep all existing workspace changes outside the MkDocs site work intact.
- Use the build itself as the red/green loop: define the full desired site contract in `mkdocs.yml`, watch `mkdocs build` fail before the symlinked content exists, then wire the content directories until the build is green.
- Keep generated output under `target/mkdocs` so the existing root `.gitignore` continues to ignore build artifacts.

### Task 1: Define the MkDocs site contract and watch the build fail

**Files:**
- Create: `mkdocs.yml`
- Create: `docs-site/index.md`
- Create: `docs-site/concurrency/index.md`
- Create: `docs-site/redis/index.md`
- Create: `docs-site/jdk/index.md`
- Create: `docs-site/jvm/index.md`
- Create: `docs-site/project-docs/index.md`

- [ ] **Step 1: Create `mkdocs.yml` with the full intended site structure**

```yaml
site_name: Java Learning Docs
site_dir: target/mkdocs
docs_dir: docs-site
strict: true

theme:
  name: mkdocs

nav:
  - Home: index.md
  - Concurrency:
      - Overview: concurrency/index.md
      - JMM Notes: concurrency/content/jmm-notes.md
      - Volatile and JMM: concurrency/content/volatile-jmm.md
      - CAS Notes: concurrency/content/cas-notes.md
      - Synchronized Notes: concurrency/content/synchronized-notes.md
      - Wait / Notify: concurrency/content/wait-notify.md
      - LockSupport: concurrency/content/lock-support.md
      - Virtual Threads: concurrency/content/virtual-threads.md
      - JMM References:
          - JSR-133 FAQ: concurrency/content/references/jmm/jsr-133-faq.md
          - JSR-133 Final Release: concurrency/content/references/jmm/jsr-133-final-release.md
          - JSR 133 Formalization: concurrency/content/references/jmm/jsr133.md
          - Java Memory Model Reference Page: concurrency/content/references/jmm/java-memory-model-reference-page.md
          - Double-Checked Locking Is Broken: concurrency/content/references/jmm/double-checked-locking-is-broken.md
  - Redis:
      - Overview: redis/index.md
      - HyperLogLog: redis/content/hyperloglog.md
      - ListPack: redis/content/listpack.md
      - Redisson Watchdog: redis/content/redisson-watchdog.md
  - JDK:
      - Overview: jdk/index.md
      - HashMap: jdk/content/hashmap.md
  - JVM:
      - Overview: jvm/index.md
      - JVM Memory: jvm/content/jvm-memory.md
  - Project Docs:
      - Overview: project-docs/index.md
      - Plans:
          - Git Ignore Cleanup Design: project-docs/content/plans/2026-03-05-gitignore-design.md
          - Git Ignore Cleanup Plan: project-docs/content/plans/2026-03-05-gitignore.md
      - Superpowers Specs:
          - Bloom Filter Cache Guard Design: project-docs/content/superpowers/specs/2026-03-20-bloom-filter-cache-guard-design.md
          - HyperLogLog Daily Active User Counter Design: project-docs/content/superpowers/specs/2026-03-21-hyperloglog-daily-active-user-counter-design.md
          - HyperLogLog Design: project-docs/content/superpowers/specs/2026-03-21-hyperloglog-design.md
          - Redis Module Design: project-docs/content/superpowers/specs/2026-03-21-redis-module-design.md
          - Redisson Lock Usage Design: project-docs/content/superpowers/specs/2026-03-22-redisson-lock-usage-design.md
          - NIO Non-Blocking Echo Client/Server Design: project-docs/content/superpowers/specs/2026-03-23-nio-non-blocking-round-trip-design.md
          - MkDocs Aggregated Docs Site Design: project-docs/content/superpowers/specs/2026-03-30-mkdocs-docs-site-design.md
      - Superpowers Plans:
          - Bloom Filter Cache Guard Implementation: project-docs/content/superpowers/plans/2026-03-20-bloom-filter-cache-guard.md
          - HyperLogLog Daily Active User Counter Implementation: project-docs/content/superpowers/plans/2026-03-21-hyperloglog-daily-active-user-counter.md
          - HyperLogLog Implementation: project-docs/content/superpowers/plans/2026-03-21-hyperloglog-implementation.md
          - Redis Module Implementation: project-docs/content/superpowers/plans/2026-03-21-redis-module-implementation.md
          - NIO Non-Blocking Echo Client/Server Simplification: project-docs/content/superpowers/plans/2026-03-23-nio-non-blocking-round-trip-implementation.md
```

- [ ] **Step 2: Create the home and project-docs landing pages**

Create `docs-site/index.md` with:

```markdown
# Java Learning Docs

This site aggregates the repository's learning notes and project documentation into one
browser-readable entry point.

## Sections

- [Concurrency](concurrency/index.md): Java concurrency notes, JMM material, and reference pages.
- [Redis](redis/index.md): Redis data structure and Redisson notes.
- [JDK](jdk/index.md): JDK-focused implementation notes.
- [JVM](jvm/index.md): JVM-focused learning notes.
- [Project Docs](project-docs/index.md): repository plans and design specs.

## Reading Notes

- Module sections are topic-oriented learning notes.
- Project Docs contains repository design and implementation artifacts.
- Existing module docs remain in their original directories; this site is a navigation layer.
```

Create `docs-site/project-docs/index.md` with:

```markdown
# Project Docs

This section exposes repository plans and specs from the root `docs` tree.

## Sections

- [Plans](content/plans/2026-03-05-gitignore.md)
- [Superpowers Specs](content/superpowers/specs/2026-03-30-mkdocs-docs-site-design.md)
- [Superpowers Plans](content/superpowers/plans/2026-03-23-nio-non-blocking-round-trip-implementation.md)

## Notes

- These pages describe repository changes, designs, and implementation plans.
- Topic-oriented Java notes live under the module sections.
```

- [ ] **Step 3: Create the module landing pages**

Create `docs-site/concurrency/index.md` with:

```markdown
# Concurrency Docs

This section groups the Java concurrency notes under `concurrency/docs`.

## Recommended Reading Order

1. [JMM Notes](content/jmm-notes.md)
2. [Volatile and JMM](content/volatile-jmm.md)
3. [CAS Notes](content/cas-notes.md)
4. [Synchronized Notes](content/synchronized-notes.md)
5. [Wait / Notify](content/wait-notify.md)
6. [LockSupport](content/lock-support.md)
7. [Virtual Threads](content/virtual-threads.md)

## Reference Material

- [JSR-133 FAQ](content/references/jmm/jsr-133-faq.md)
- [JSR-133 Final Release](content/references/jmm/jsr-133-final-release.md)
- [JSR 133 Formalization](content/references/jmm/jsr133.md)
- [Java Memory Model Reference Page](content/references/jmm/java-memory-model-reference-page.md)
- [Double-Checked Locking Is Broken](content/references/jmm/double-checked-locking-is-broken.md)
- Local HTML, PDF, and image assets stay under `content/references/jmm/`.
```

Create `docs-site/redis/index.md` with:

```markdown
# Redis Docs

This section groups the Redis-oriented notes under `redis/docs`.

## Topics

- [HyperLogLog](content/hyperloglog.md)
- [ListPack](content/listpack.md)
- [Redisson Watchdog](content/redisson-watchdog.md)

## Notes

- These pages are learning-oriented notes, not production guidance.
- Project-wide design and plan artifacts live under [Project Docs](../project-docs/index.md).
```

Create `docs-site/jdk/index.md` with:

```markdown
# JDK Docs

This section groups the JDK-oriented notes under `jdk/docs`.

## Topics

- [HashMap](content/hashmap.md)

## Notes

- This section is intentionally small today.
- Related repository design documents live under [Project Docs](../project-docs/index.md).
```

Create `docs-site/jvm/index.md` with:

```markdown
# JVM Docs

This section groups the JVM-oriented notes under `jvm/docs`.

## Topics

- [JVM Memory](content/jvm-memory.md)

## Notes

- This section currently focuses on memory-oriented JVM notes.
- Related repository design documents live under [Project Docs](../project-docs/index.md).
```

- [ ] **Step 4: Run the MkDocs build to verify the site contract is red**

Run: `mkdocs build`

Expected: FAIL because the navigation references `docs-site/*/content/...` pages that do not exist yet; MkDocs should report missing documentation files from the nav configuration.

### Task 2: Wire the existing docs trees into the aggregation layer and make the site build pass

**Files:**
- Create: `docs-site/project-docs/content`
- Create: `docs-site/concurrency/content`
- Create: `docs-site/redis/content`
- Create: `docs-site/jdk/content`
- Create: `docs-site/jvm/content`
- Verify: `mkdocs.yml`
- Verify: `docs-site/index.md`
- Verify: `docs-site/concurrency/index.md`
- Verify: `docs-site/redis/index.md`
- Verify: `docs-site/jdk/index.md`
- Verify: `docs-site/jvm/index.md`
- Verify: `docs-site/project-docs/index.md`

- [ ] **Step 1: Create the relative symbolic links to the existing docs trees**

Run:

```bash
ln -s ../../docs docs-site/project-docs/content
ln -s ../../concurrency/docs docs-site/concurrency/content
ln -s ../../redis/docs docs-site/redis/content
ln -s ../../jdk/docs docs-site/jdk/content
ln -s ../../jvm/docs docs-site/jvm/content
```

Expected: each `content` path exists as a symbolic link that points back to the original source docs tree.

- [ ] **Step 2: Verify the symbolic links before rebuilding**

Run:

```bash
test -L docs-site/project-docs/content
test -L docs-site/concurrency/content
test -L docs-site/redis/content
test -L docs-site/jdk/content
test -L docs-site/jvm/content
```

Expected: all `test` commands exit successfully.

- [ ] **Step 3: Run the MkDocs build again to verify the site is green**

Run: `mkdocs build`

Expected: PASS with no missing-page errors, and the generated site is written to `target/mkdocs`.

- [ ] **Step 4: Commit the aggregated MkDocs site**

```bash
git add mkdocs.yml docs-site
git commit -m "feat(docs): add aggregated MkDocs site"
```

### Task 3: Verify generated output, static assets, and clean repository behavior

**Files:**
- Verify: `mkdocs.yml`
- Verify: `docs-site/index.md`
- Verify: `docs-site/concurrency/index.md`
- Verify: `docs-site/redis/index.md`
- Verify: `docs-site/jdk/index.md`
- Verify: `docs-site/jvm/index.md`
- Verify: `docs-site/project-docs/index.md`
- Verify: `docs-site/project-docs/content`
- Verify: `docs-site/concurrency/content`
- Verify: `docs-site/redis/content`
- Verify: `docs-site/jdk/content`
- Verify: `docs-site/jvm/content`

- [ ] **Step 1: Run the final MkDocs build**

Run: `mkdocs build`

Expected: PASS, confirming the committed site still builds cleanly.

- [ ] **Step 2: Verify representative generated pages and static assets exist**

Run:

```bash
test -f target/mkdocs/index.html
test -f target/mkdocs/concurrency/index.html
test -f target/mkdocs/concurrency/content/jmm-notes/index.html
test -f target/mkdocs/concurrency/content/references/jmm/jsr-133-faq.html
test -f target/mkdocs/concurrency/content/references/jmm/jsr133.pdf
test -f target/mkdocs/concurrency/content/references/jmm/jsr133-figures/figure-03.png
test -f target/mkdocs/project-docs/content/superpowers/specs/2026-03-30-mkdocs-docs-site-design/index.html
```

Expected: all `test` commands exit successfully, proving that topic pages, project-doc pages, HTML mirrors, PDF assets, and image assets are all present in the built site.

- [ ] **Step 3: Verify the repository stays clean after building**

Run:

```bash
git check-ignore -q target/mkdocs/index.html
git status --short -- mkdocs.yml docs-site
```

Expected: `git check-ignore` exits successfully for the generated site output, and `git status --short -- mkdocs.yml docs-site` prints nothing because the site files are committed cleanly.
