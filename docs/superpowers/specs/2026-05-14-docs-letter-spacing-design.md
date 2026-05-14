# Docs Letter Spacing Configuration Design

## Goal

Add configurable character spacing for MkDocs article text, so Chinese-heavy book pages can be tuned for readability without editing generated files.

## Scope

- Add a site default under `mkdocs/mkdocs.yml` in `extra.layout.letter_spacing`.
- Add a runtime control to the existing top-bar layout switcher.
- Persist user choice in `localStorage`, matching the existing width settings.
- Apply spacing to the main article content only, not side navigation, table of contents, code blocks, diagrams, or generated UI controls.
- Update README and verification script coverage.

## Non-Goals

- No changes under `references/`.
- No edits to generated files under `mkdocs/site/`.
- No font-size, line-height, or paragraph spacing controls in this change.
- No per-page front matter override.

## Configuration

`extra.layout.letter_spacing` accepts four values:

- `compact`: current browser/default spacing.
- `normal`: light spacing for dense Chinese text.
- `wide`: wider spacing for long-form reading.
- `extra`: largest supported spacing.

The default should be `compact` to preserve current rendering until the site owner opts in.

## Architecture

Extend the existing layout configuration path instead of adding a separate system:

- `mkdocs/hooks/layout_width.py` validates the configured value, adds a `data-docs-letter-spacing` attribute to `<html>`, and includes it in the early restore script.
- `mkdocs/docs/stylesheets/extra.css` maps `html[data-docs-letter-spacing="..."]` to a CSS custom property and applies it to `.md-content__inner` reading text.
- `mkdocs/docs/javascripts/layout-width.js` adds a "字距" row to the existing "布局" panel and stores the user choice in `java-learning-docs-letter-spacing`.
- `mkdocs/scripts/verify_layout_width.sh` verifies the generated attribute, restore script key, runtime switcher key, and CSS rules.
- `mkdocs/README.md` documents the new setting and storage key.

## User Experience

The existing "布局" button remains the single entry point. Its panel gains a "字距" row with four options. Changing the option updates the visible article text immediately and is remembered for future visits in the same browser.

## Testing

Run:

```bash
mkdocs/scripts/verify_layout_width.sh
```

This script builds the documentation site and checks the generated HTML, CSS, and JavaScript for the width and letter-spacing configuration contracts.

## Self-Review

- No unresolved markers or decisions remain.
- Scope is limited to MkDocs source files and scripts.
- The design follows the existing width configuration pattern.
- Generated files and reference material remain untouched.
