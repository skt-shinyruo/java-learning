---
name: save-conversation-to-docs
description: Use when the user asks to save, preserve, record, capture, add, or turn prior conversation content into repository documentation, notes, learning material, a knowledge base, Markdown docs, MkDocs pages, README sections, or similar long-lived project docs.
---

# Save Conversation to Docs

Turn useful conversation content into durable repository documentation. Do not transcribe chat logs; extract stable knowledge, rewrite it in the repository's documentation style, and place it where future readers would expect to find it.

## Workflow

1. Identify the conversation scope the user means by "above", "this", or "the previous content". If the boundary is ambiguous and choosing wrong would save unrelated content, ask once; otherwise use the most recent coherent topic.
2. Read project instructions and docs structure first: `AGENTS.md`, README files, docs config, and existing docs indexes as relevant.
3. Search for existing documents about the topic. Prefer semantic/codebase search when exact locations are unknown; use exact search for known titles, headings, config entries, or nav labels.
4. Choose the landing spot:
   - Append to an existing source doc when the topic naturally extends it.
   - Create a new source doc only when the topic is independent or no existing doc fits.
   - If creating a doc that belongs to a generated site, update the source navigation, index, or summary files required by that site.
5. Draft documentation in a durable style:
   - Convert Q&A into explanatory prose.
   - Keep only stable facts, examples, diagrams, commands, and caveats.
   - Preserve important nuance from the conversation, including assumptions and edge cases.
   - Add cross-links to related docs when useful.
6. Edit only source files. Use the repository's normal editing and formatting conventions.
7. Validate with the narrowest meaningful command available for the docs system. For MkDocs, run the appropriate `mkdocs build -f ...`; for other docs, run the local build, link check, or at minimum inspect the diff when no command exists.
8. Report the files changed, where the content was placed, whether navigation/index files changed, and the verification command and result.

## Placement Rules

- Prefer source docs over generated output. Never edit directories like `site/`, `dist/`, `build/`, or generated API docs unless the user explicitly asks.
- Respect protected or reference-only areas. Do not edit `references/`, vendored docs, third-party material, or archived/generated snapshots unless the user explicitly requests it.
- If a docs mirror is a symlink to source docs, edit the source path and mention the relationship only if it matters.
- Match local heading style, naming, relative links, code fence languages, and numbering conventions.
- Avoid duplicating an explanation that already exists. Extend or link instead.
- If a new doc is created, make it discoverable through the repository's existing nav or index pattern.

## Quality Bar

Before finishing, check:

- The saved content reads like documentation, not a transcript.
- The target location is justified by surrounding topics.
- The new text does not contradict nearby docs.
- Examples are small, accurate, and reusable.
- Links are relative and valid for the docs system.
- Generated files or unrelated user changes were not modified.

## When to Ask

Proceed without asking when there is a clear best destination and the content is safe to save. Ask the user only when:

- Multiple destinations are equally plausible and saving to one would be hard to undo.
- A new documentation category or navigation structure must be introduced.
- The content appears private, temporary, speculative, or not appropriate for long-lived docs.
- Completing the request requires editing protected/reference/generated areas.

## Common Mistakes

- Saving the raw conversation instead of a distilled explanation.
- Creating a new page when an existing page should be extended.
- Updating generated docs instead of source docs.
- Forgetting to update navigation or index files for a new page.
- Claiming completion without running or attempting the docs verification command.
