#!/usr/bin/env python3

from __future__ import annotations

import argparse
import re
from os.path import relpath
from pathlib import Path

import fitz


FULLWIDTH_SPACE = "\u3000"
ROOT_HEADINGS = {"目录", "前言", "致谢", "后记"}
MONO_FONT_MARKERS = ("Courier", "Mono", "Consolas")
TOC_ENTRY_START_RE = re.compile(
    r"(第[一二三四五六七八九十百零0-9]+部分|第[0-9]+章|附录\s*[A-ZＡ-Ｚ]|\d+(?:\.\d+)+)"
)


def iter_spans(block: dict) -> list[dict]:
    return [span for line in block["lines"] for span in line["spans"]]


def block_lines(block: dict) -> list[str]:
    lines: list[str] = []
    for line in block["lines"]:
        text = "".join(span["text"] for span in line["spans"]).rstrip()
        if text:
            lines.append(text)
    return lines


def is_ascii_word_char(char: str) -> bool:
    return char.isascii() and char.isalnum()


def merge_wrapped_lines(lines: list[str]) -> str:
    merged = ""
    for raw_line in lines:
        line = raw_line.strip()
        if not line:
            continue
        if not merged:
            merged = line
            continue
        prev = merged[-1]
        nxt = line[0]
        if is_ascii_word_char(prev) and is_ascii_word_char(nxt):
            merged += " " + line
        else:
            merged += line
    return merged


def block_text(block: dict) -> str:
    return merge_wrapped_lines(block_lines(block))


def is_code_block(block: dict) -> bool:
    spans = iter_spans(block)
    if not spans:
        return False

    total_chars = sum(len(span["text"]) for span in spans) or 1
    mono_chars = sum(
        len(span["text"])
        for span in spans
        if any(marker in span["font"] for marker in MONO_FONT_MARKERS)
    )
    sizes = [span["size"] for span in spans]
    avg_size = sum(sizes) / len(sizes)
    text = block_text(block)

    if mono_chars / total_chars >= 0.45:
        return True
    if avg_size <= 7.2 and any(
        marker in span["font"] for span in spans for marker in MONO_FONT_MARKERS
    ):
        return True
    if text.startswith("Exception in thread ") or text.startswith("Caused by:"):
        return True
    return False


def heading_level(text: str) -> int | None:
    stripped = text.strip()
    if stripped in ROOT_HEADINGS:
        return 1
    if re.match(r"^第[一二三四五六七八九十百零0-9]+部分", stripped):
        return 1
    if re.match(r"^第[0-9]+章", stripped):
        return 2
    if re.match(r"^附录\s*[A-ZＡ-Ｚ]", stripped):
        return 2

    match = re.match(r"^(\d+(?:\.\d+)+)" + FULLWIDTH_SPACE + r"?.+", stripped)
    if match:
        depth = match.group(1).count(".")
        return min(6, depth + 2)
    return None


def is_toc_like_line(text: str) -> bool:
    stripped = text.strip()
    return heading_level(stripped) is not None or bool(re.match(r"^(前言|致谢|后记)$", stripped))


def is_toc_block(block: dict) -> bool:
    lines = block_lines(block)
    if len(lines) >= 2 and all(is_toc_like_line(line) for line in lines):
        return True
    if len(lines) < 3:
        return False
    toc_like_count = sum(1 for line in lines if is_toc_like_line(line))
    return toc_like_count >= max(3, int(len(lines) * 0.6))


def split_toc_line(text: str) -> list[str]:
    stripped = text.strip()
    starts = [
        match.start() for match in TOC_ENTRY_START_RE.finditer(stripped) if match.start() > 0
    ]
    if not starts:
        return [stripped]

    parts: list[str] = []
    last = 0
    for start in starts:
        part = stripped[last:start].strip()
        if part:
            parts.append(part)
        last = start
    tail = stripped[last:].strip()
    if tail:
        parts.append(tail)
    return parts


def markdown_for_text_block(block: dict) -> str:
    lines = block_lines(block)
    if not lines:
        return ""

    if is_code_block(block):
        return "```text\n" + "\n".join(lines) + "\n```"

    if is_toc_block(block):
        parts: list[str] = []
        for line in lines:
            for part in split_toc_line(line):
                stripped = part.strip()
                if not stripped:
                    continue
                level = heading_level(stripped)
                if level is not None:
                    parts.append(f"{'#' * level} {stripped}")
                else:
                    parts.append(stripped)
        return "\n\n".join(parts)

    text = merge_wrapped_lines(lines)
    level = heading_level(text)
    if level is not None:
        return f"{'#' * level} {text}"
    return text


def save_image(block: dict, page_number: int, image_index: int, images_dir: Path) -> str:
    ext = block.get("ext") or "jpeg"
    image_name = f"page-{page_number:04d}-image-{image_index:02d}.{ext}"
    image_path = images_dir / image_name
    image_path.write_bytes(block["image"])
    return image_name


def block_sort_key(block: dict) -> tuple[float, float]:
    x0, y0, _, _ = block["bbox"]
    return (round(y0, 3), round(x0, 3))


def convert(pdf_path: Path, output_md_path: Path, images_dir: Path) -> None:
    images_dir.mkdir(parents=True, exist_ok=True)
    output_md_path.parent.mkdir(parents=True, exist_ok=True)

    doc = fitz.open(pdf_path)
    pieces: list[str] = []
    image_prefix = Path(relpath(images_dir, output_md_path.parent)).as_posix()

    for page_index in range(doc.page_count):
        page_number = page_index + 1
        page = doc[page_index]
        page_dict = page.get_text("dict")
        blocks = sorted(page_dict["blocks"], key=block_sort_key)

        page_parts: list[str] = [f"<!-- page {page_number:04d} -->"]
        image_index = 0

        for block in blocks:
            if block["type"] == 1:
                image_index += 1
                image_name = save_image(block, page_number, image_index, images_dir)
                page_parts.append(
                    f"![第{page_number}页图片{image_index}]({image_prefix}/{image_name})"
                )
                continue

            if block["type"] != 0:
                continue

            markdown = markdown_for_text_block(block)
            if markdown:
                page_parts.append(markdown)

        pieces.append("\n\n".join(page_parts).strip())

    output_md_path.write_text("\n\n".join(pieces).rstrip() + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Convert a PDF into Markdown, exporting embedded images alongside the document."
    )
    parser.add_argument("pdf", type=Path, help="Source PDF path")
    parser.add_argument("output", type=Path, help="Target Markdown path")
    parser.add_argument(
        "--images-dir",
        type=Path,
        required=True,
        help="Directory where extracted images are written",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    convert(args.pdf.resolve(), args.output.resolve(), args.images_dir.resolve())


if __name__ == "__main__":
    main()

