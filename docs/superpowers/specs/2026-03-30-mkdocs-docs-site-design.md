# MkDocs Aggregated Docs Site Design

## Background

This repository is a multi-module Java learning project. Its learning notes and design documents are
already useful, but they are currently spread across several directories:

- `docs`
- `concurrency/docs`
- `redis/docs`
- `jdk/docs`
- `jvm/docs`

Most of the content is Markdown. Some reference material also includes static HTML, PDF, and image
assets under the existing docs trees.

The user wants a browser-readable documentation site for the whole repository, but does not want a
large restructure of the repository layout or new toolchain dependencies beyond what is already
available locally. The current environment already has `mkdocs` installed, which makes it a good
fit for a lightweight documentation entry layer.

## Goals

- Add one browser-readable documentation site for the whole repository
- Keep all existing Markdown documents in their current source locations
- Aggregate root docs and module docs into one MkDocs site
- Keep the setup dependency-light and runnable with the locally installed `mkdocs`
- Make the navigation clearer than a raw directory listing
- Preserve access to existing static reference assets such as HTML, PDF, and images
- Keep the first version small and easy to maintain

## Non-Goals

- No migration of all existing documentation into a new canonical directory tree
- No rewrite of existing Markdown content just to fit a new site structure
- No extra MkDocs plugins or third-party themes in the first version
- No automatic taxonomy, tagging, or search enhancement work
- No attempt to normalize all historical relative-link styles across old documents
- No broad cleanup of document wording, formatting, or information architecture outside the new site

## Site Strategy

Use MkDocs as a repository-level documentation shell rather than as the new source of truth for
documentation content.

The design should introduce a small aggregation layer that points at the existing documentation
trees. Readers browse one coherent site in the browser, while authors continue editing documents in
their current module-local locations.

The recommended approach is:

- add a repository-level `mkdocs.yml`
- add a dedicated aggregation directory named `docs-site`
- place lightweight entry pages in `docs-site`
- expose the existing documentation trees inside `docs-site` through symbolic links

This design keeps source ownership stable while giving MkDocs one unified `docs_dir`.

## Placement

Create:

- `mkdocs.yml`
- `docs-site/index.md`
- `docs-site/project-docs/index.md`
- `docs-site/concurrency/index.md`
- `docs-site/redis/index.md`
- `docs-site/jdk/index.md`
- `docs-site/jvm/index.md`

Create symbolic links inside `docs-site` that point to the existing documentation directories:

- `docs-site/project-docs/content` -> `docs`
- `docs-site/concurrency/content` -> `concurrency/docs`
- `docs-site/redis/content` -> `redis/docs`
- `docs-site/jdk/content` -> `jdk/docs`
- `docs-site/jvm/content` -> `jvm/docs`

The `content` subdirectory name keeps the generated site structure easy to reason about and avoids
confusion between module landing pages and module source content.

## Navigation Design

The site navigation should be hand-written in `mkdocs.yml`, not auto-generated.

The first version should optimize for readability and learning order rather than for exhaustively
mirroring every directory automatically.

Recommended top-level navigation:

- `Home`
- `Concurrency`
- `Redis`
- `JDK`
- `JVM`
- `Project Docs`

### Home

The home page should explain:

- the repository is a Java learning workspace
- the site aggregates notes from multiple modules
- module landing pages provide reading guidance
- some pages are design/planning artifacts rather than topic tutorials

### Module landing pages

Each module landing page should:

- briefly describe the module topic
- point readers to the most useful documents first
- link into the actual documents under the linked `content` subtree

These landing pages should remain short. They are navigation aids, not duplicate copies of the
real document bodies.

### Concurrency section

The `Concurrency` navigation should include the primary topic notes directly because they form a
clear learning sequence:

- `jmm-notes.md`
- `volatile-jmm.md`
- `cas-notes.md`
- `synchronized-notes.md`
- `wait-notify.md`
- `lock-support.md`
- `virtual-threads.md`

The JMM reference material under `references/jmm` should be grouped separately below the main topic
pages because those files act as supporting reference material rather than as the best first entry
point.

### Redis, JDK, and JVM sections

These sections should expose each current Markdown document explicitly through the navigation. Their
content sets are small enough that a hand-written list is clear and stable.

### Project Docs section

The root `docs` tree should be presented as repository process/project material, not mixed into the
topic modules.

Recommended grouping:

- `Plans`
- `Superpowers Specs`
- `Superpowers Plans`

This makes it clear that these are project artifacts rather than Java concept notes.

## Static Assets and Relative Links

The aggregated site must preserve access to existing static assets already referenced by the
Markdown files, especially under `concurrency/docs/references/jmm`.

Important requirements:

- image references already used by Markdown files must continue to resolve
- links from Markdown pages to neighboring Markdown files should keep working
- links from Markdown files to local HTML and PDF files should remain reachable as static files

Using symbolic links to the original documentation trees is the preferred design because it lets
MkDocs build from a single directory while keeping each document next to its existing local assets.

## Theme and Presentation

Use the default MkDocs theme for the first version.

Rationale:

- no extra dependency installation
- predictable behavior in the current local environment
- sufficient for a learning-oriented internal documentation site

The first version should favor stability over visual customization.

## Local Workflow

The intended local workflow should be straightforward:

1. edit existing documents in their original module directories
2. edit `mkdocs.yml` or `docs-site` landing pages when navigation needs to change
3. run `mkdocs serve`
4. read the site in the browser at `http://127.0.0.1:8000`

This keeps documentation authoring and documentation browsing separate without introducing a build
generation step.

## Validation Strategy

Primary verification command:

- `mkdocs build`

Local browsing command:

- `mkdocs serve`

Validation should confirm:

- the site builds successfully
- the intended navigation entries appear
- representative topic pages render
- a reference page with local images renders correctly
- links to local HTML and PDF assets remain reachable

## Implementation Constraints

- keep the implementation dependency-free beyond the existing local `mkdocs` installation
- use repository-relative symbolic links rather than content duplication
- keep landing pages short and explanatory
- do not move existing documentation files
- do not change unrelated module build files
- prefer explicit navigation ordering over automatic discovery

## Future Enhancements

Possible follow-up work after the initial site is stable:

- add a small helper script to recreate symbolic links if needed
- add a nicer MkDocs theme once dependency policy changes
- expand landing pages with recommended reading order for more modules
- add a contribution note describing how to register new docs in navigation
