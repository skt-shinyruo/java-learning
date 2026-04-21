#!/usr/bin/env python3
import re
from pathlib import Path


BOOK_DIR = Path("references/深入理解Java虚拟机_JVM高级特性与最佳实践_第3版")
MAIN_MD = BOOK_DIR / "深入理解Java虚拟机_JVM高级特性与最佳实践_第3版.md"
CHAPTERS_DIR = BOOK_DIR / "chapters"

FOOTNOTE_LINE_RE = re.compile(r"^\[\d+\]")
FOOTNOTE_MARKER_MISSING_SPACE_RE = re.compile(r"^(\[\d+\])(\S)")
CODE_FENCE_RE = re.compile(r"^```")


def _fix_text(text: str) -> tuple[str, bool]:
    lines = text.splitlines(True)
    out: list[str] = []
    changed = False
    in_code_block = False

    i = 0
    while i < len(lines):
        line = lines[i]

        # Avoid touching anything inside fenced code blocks.
        if CODE_FENCE_RE.match(line):
            in_code_block = not in_code_block
            out.append(line)
            i += 1
            continue

        if (not in_code_block) and FOOTNOTE_LINE_RE.match(line):
            # Normalize marker spacing: PDF prints "[n] " with a space.
            line_fixed = FOOTNOTE_MARKER_MISSING_SPACE_RE.sub(r"\1 \2", line)
            if line_fixed != line:
                changed = True
                line = line_fixed

            j = i + 1
            while j < len(lines) and lines[j].strip() == "":
                j += 1

            has_blank_between = j > i + 1
            if has_blank_between and j < len(lines) and FOOTNOTE_LINE_RE.match(lines[j]):
                # The PDF prints footnote items as tight consecutive lines.
                # In Markdown, keep a single paragraph and force line breaks via <br>.
                new_line = line
                if not line.rstrip("\n").endswith("<br>"):
                    new_line = line.rstrip("\n") + "<br>\n"
                if new_line != line:
                    changed = True
                if has_blank_between:
                    changed = True
                out.append(new_line)
                i = j
                continue

        out.append(line)
        i += 1

    return "".join(out), changed


def fix_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    fixed, changed = _fix_text(text)
    if changed:
        path.write_text(fixed, encoding="utf-8")
    return changed


def main() -> None:
    paths = [MAIN_MD] + [p for p in sorted(CHAPTERS_DIR.glob("*.md")) if p.name != "README.md"]
    changed = [p for p in paths if fix_file(p)]
    print(f"changed_files {len(changed)}")
    for p in changed:
        print(p.as_posix())


if __name__ == "__main__":
    main()

