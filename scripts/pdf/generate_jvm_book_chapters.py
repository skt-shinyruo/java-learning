#!/usr/bin/env python3

from __future__ import annotations

import argparse
import importlib.util
import re
import sys
import tempfile
from pathlib import Path
from typing import Sequence


BOOK_DIR = Path("references/深入理解Java虚拟机_JVM高级特性与最佳实践_第3版")
PDF_PATH = BOOK_DIR / "深入理解Java虚拟机：JVM高级特性与最佳实践（第3版）.pdf"
CHAPTERS_DIR = BOOK_DIR / "chapters"
IMAGES_DIR = BOOK_DIR / "images"
PAGE_IMAGE_NAME_RE = re.compile(r"page-\d{4}-image-\d+\.(?:jpe?g|png)$", re.IGNORECASE)
MD_IMAGE_RE = re.compile(r"(!\[[^\]]*\]\()([^)]+)(\))")


def load_local_module(name: str, module_path: Path):
    spec = importlib.util.spec_from_file_location(name, module_path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def chapter_heading(path: Path) -> str:
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#"):
            return line.strip()
    raise RuntimeError(f"No heading found in {path}")


def split_main_markdown_into_chapters(
    main_text: str,
    chapter_paths: Sequence[Path],
) -> dict[Path, str]:
    main_lines = main_text.splitlines()
    headings = [(path, chapter_heading(path)) for path in chapter_paths]
    start_indexes: list[int] = []

    for path, heading in headings:
        matches = [i for i, line in enumerate(main_lines) if line.strip() == heading]
        if not matches:
            raise RuntimeError(f"Heading not found in main markdown: {path.name} -> {heading}")
        start_indexes.append(matches[-1])

    chunks: dict[Path, str] = {}
    for idx, (path, _) in enumerate(headings):
        start = start_indexes[idx]
        end = start_indexes[idx + 1] if idx + 1 < len(start_indexes) else len(main_lines)
        chunk = "\n".join(main_lines[start:end]).strip() + "\n"
        chunk = normalize_chapter_image_links(chunk)
        chunks[path] = chunk

    return chunks


def normalize_chapter_image_links(markdown: str) -> str:
    def repl(match: re.Match[str]) -> str:
        target = match.group(2)
        if re.match(r"^[a-z]+://", target, re.IGNORECASE) or target.startswith("#"):
            return match.group(0)

        image_name = Path(target).name
        if not PAGE_IMAGE_NAME_RE.fullmatch(image_name):
            return match.group(0)

        return f"{match.group(1)}../images/{image_name}{match.group(3)}"

    return MD_IMAGE_RE.sub(repl, markdown)


def write_chapter_markdown(chunks: dict[Path, str]) -> None:
    for path, chunk in chunks.items():
        path.write_text(chunk, encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate chapter-only Markdown outputs for the JVM book PDF."
    )
    parser.add_argument("--pdf", type=Path, default=PDF_PATH)
    parser.add_argument("--book-dir", type=Path, default=BOOK_DIR)
    parser.add_argument("--chapters-dir", type=Path, default=CHAPTERS_DIR)
    parser.add_argument("--images-dir", type=Path, default=IMAGES_DIR)
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    scripts_dir = Path(__file__).resolve().parent
    convert_module = load_local_module("convert_pdf_to_markdown", scripts_dir / "convert_pdf_to_markdown.py")
    footnote_module = load_local_module("fix_footnote_spacing", scripts_dir / "fix_footnote_spacing.py")
    convert = convert_module.convert
    fix_file = footnote_module.fix_file

    chapter_paths = [p for p in sorted(args.chapters_dir.glob("*.md")) if p.name != "README.md"]
    if not chapter_paths:
        raise RuntimeError(f"No chapter markdown files found in {args.chapters_dir}")

    with tempfile.TemporaryDirectory() as tmpdir:
        temp_main = Path(tmpdir) / "book.md"
        convert(args.pdf.resolve(), temp_main, args.images_dir.resolve())
        chunks = split_main_markdown_into_chapters(
            temp_main.read_text(encoding="utf-8"),
            chapter_paths,
        )

    write_chapter_markdown(chunks)

    for path in chapter_paths:
        fix_file(path)


if __name__ == "__main__":
    main()
