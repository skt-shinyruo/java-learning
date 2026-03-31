# Git Ignore Cleanup Plan

See: [Git Ignore Cleanup Design](2026-03-05-gitignore-design.md)

---

## Checklist

- [ ] Inventory current ignore rules in root `.gitignore` and remove duplicates.
- [ ] Ensure all build outputs remain ignored (at least `target/`).
- [ ] Ensure IDE/editor/OS/tooling metadata remains ignored as needed.
- [ ] Verify MkDocs output directory is ignored (`target/mkdocs`).
- [ ] Sanity check with `git status` (clean working tree after builds).

