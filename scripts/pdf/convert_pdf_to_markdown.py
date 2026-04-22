#!/usr/bin/env python3

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from os.path import relpath
from pathlib import Path
from typing import Sequence

import fitz
from pdfminer.high_level import extract_pages
from pdfminer.layout import LTTextContainer, LTTextLine


FULLWIDTH_SPACE = "\u3000"
ROOT_HEADINGS = {"目录", "前言", "致谢", "后记"}
MONO_FONT_MARKERS = ("Courier", "Mono", "Consolas")
TOC_ENTRY_START_RE = re.compile(
    r"(第[一二三四五六七八九十百零0-9]+部分|第[0-9]+章|附录\s*[A-ZＡ-Ｚ]|\d+(?:\.\d+)+)"
)
WHITESPACE_RE = re.compile(r"\s+")
PDFMINER_BLOCK_Y_MARGIN = 1.5
PDFMINER_BLOCK_X_MARGIN = 6.0
FENCED_CODE_BLOCK_RE = re.compile(r"\A```([^\n`]*)\n(.*)\n```\Z", re.S)
BYTECODE_HEADER_RE = re.compile(
    r"(?mi)^\s*(Code:|LineNumberTable:|LocalVariableTable:|Exception table:|StackMapTable:|Constant pool:)\s*$"
)
BYTECODE_OPCODE_RE = re.compile(r"(?m)^\s*\d+:\s+[a-z][a-z0-9_]*\b")
BYTECODE_META_RE = re.compile(r"(?mi)^\s*(Stack|Locals|Args_size)=\d+")
JAVA_TYPE_DECL_RE = re.compile(
    r"(?m)^\s*(?:public|protected|private)?\s*(?:static\s+)?(?:abstract\s+)?(?:final\s+)?"
    r"(?:class|interface|enum|record)\s+\w+"
)
JAVA_METHOD_DECL_RE = re.compile(
    r"(?m)^\s*(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?"
    r"(?:native\s+)?(?:abstract\s+)?(?:<[^>]+>\s+)?[\w<>\[\]?.,]+\s+\w+\s*\([^\n;]*\)"
    r"\s*(?:throws\b[^{]+)?\{?$"
)
JAVA_FIELD_DECL_RE = re.compile(
    r"(?m)^\s*(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:volatile\s+)?"
    r"(?!goto\b)[\w<>\[\]?.,]+\s+\w+\s*(?:=[^\n;]+)?;$"
)
CPP_POINTER_FUNCTION_RE = re.compile(
    r"(?m)^\s*[\w:<>]+\s+\w+\s*\([^)]*\*\s*\w[^)]*\)\s*\{?$"
)
CPP_POINTER_ASSIGN_RE = re.compile(r"(?m)^\s*\*\w+\s*=")


@dataclass(frozen=True)
class TextLine:
    text: str
    bbox: tuple[float, float, float, float]


def iter_spans(block: dict) -> list[dict]:
    return [span for line in block["lines"] for span in line["spans"]]


def fitz_block_lines(block: dict) -> list[TextLine]:
    lines: list[TextLine] = []
    for line in block["lines"]:
        text = "".join(span["text"] for span in line["spans"]).rstrip()
        if not text:
            continue
        lines.append(TextLine(text=text, bbox=tuple(line["bbox"])))
    return lines


def extract_pdfminer_page_lines(
    pdf_path: Path,
    page_numbers: Sequence[int],
) -> dict[int, list[TextLine]]:
    selected_pages = sorted(dict.fromkeys(page_numbers))
    result: dict[int, list[TextLine]] = {}
    layouts = extract_pages(pdf_path, page_numbers=[page - 1 for page in selected_pages])

    for page_number, layout in zip(selected_pages, layouts):
        lines: list[TextLine] = []
        for elem in layout:
            if not isinstance(elem, LTTextContainer):
                continue
            for child in elem:
                if not isinstance(child, LTTextLine):
                    continue
                text = child.get_text().rstrip("\n")
                if not text.strip():
                    continue
                lines.append(TextLine(text=text, bbox=tuple(child.bbox)))

        lines.sort(key=lambda line: (-line.bbox[3], line.bbox[0]))
        result[page_number] = lines

    return result


def is_ascii_word_char(char: str) -> bool:
    return char.isascii() and char.isalnum()


def merge_wrapped_lines(lines: Sequence[str]) -> str:
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


def block_text(block: dict, lines: Sequence[str] | None = None) -> str:
    return merge_wrapped_lines(lines or [line.text for line in fitz_block_lines(block)])


def normalize_compare_text(text: str) -> str:
    return WHITESPACE_RE.sub("", text)


def parse_fenced_code_block(block: str) -> tuple[str, list[str]] | None:
    match = FENCED_CODE_BLOCK_RE.match(block)
    if not match:
        return None
    language = match.group(1).strip()
    body = match.group(2)
    return language, body.splitlines()


def make_fenced_code_block(lines: Sequence[str], language: str) -> str:
    return f"```{language}\n" + "\n".join(lines) + "\n```"


def infer_code_language(lines: Sequence[str], context: str = "") -> str:
    text = "\n".join(lines)
    stripped_lines = [line.strip() for line in lines if line.strip()]
    first_line = stripped_lines[0] if stripped_lines else ""
    context_lower = context.lower()
    caption_lower = next((line.strip().lower() for line in reversed(context.splitlines()) if line.strip()), "")
    text_lower = text.lower()

    if (
        re.search(r"(?:的字节码|字节码表示|常量池内容|反汇编)", caption_lower) is not None
        or BYTECODE_HEADER_RE.search(text) is not None
        or BYTECODE_OPCODE_RE.search(text) is not None
        or BYTECODE_META_RE.search(text) is not None
    ):
        return "text"

    looks_like_ini = (
        any(line in {"-vm", "-startup", "-vmargs", "-showsplash", "-product"} for line in stripped_lines)
        and any(
            token in text
            for token in [
                "javaw.exe",
                "org.eclipse.",
                ".jar",
                "-XX:",
                "-Xmx",
                "-Xms",
                "-Xmn",
            ]
        )
    )
    if (".ini" in context_lower or "配置文件" in context_lower) and looks_like_ini:
        return "ini"

    if re.search(r"(?m)^[A-Za-z]:[\\/].*>", text) or re.search(r"(?m)^SET\s+[A-Z_]+=", text):
        return "bat"

    if re.search(r"(?m)^\s*(sudo\s+\S+|bash\s+\S+|make\s+\S+|\./configure\b)", text):
        return "bash"

    if (
        any(ext in context_lower for ext in [".cpp", ".cc", ".cxx", ".hpp", ".h"])
        or any(
            token in text
            for token in [
                "::",
                "NULL",
                "goto ",
                "HeapWord",
                "klassOop",
                "instanceKlass",
                "oopDesc",
                "Universe::",
                "Atomic::",
                "memset(",
            ]
        )
        or CPP_POINTER_FUNCTION_RE.search(text) is not None
        or CPP_POINTER_ASSIGN_RE.search(text) is not None
    ):
        return "cpp"

    if (
        ".java" in context_lower
        or JAVA_TYPE_DECL_RE.search(text) is not None
        or JAVA_METHOD_DECL_RE.search(text) is not None
        or JAVA_FIELD_DECL_RE.search(text) is not None
        or "system.gc();" in text_lower
        or "system.out" in text_lower
        or re.search(r"\bnew\s+[A-Z]\w*\s*\(", text)
    ):
        return "java"

    if (
        ".xml" in context_lower
        or first_line.startswith("<?xml")
        or (text.lstrip().startswith("<") and "</" in text)
    ):
        return "xml"

    if (
        ".sql" in context_lower
        or re.search(r"^\s*(select|insert|update|delete|create|alter|drop)\b", text, re.I | re.M)
    ):
        return "sql"

    if (
        first_line.startswith("#!/bin/")
        or first_line.startswith("$ ")
        or re.search(r"^\$\s*(java|javac|jps|jmap|jstack|jinfo|jstat)\b", first_line)
        or "bash-" in first_line
        or re.search(r"^\s*(java|javac|jps|jmap|jstack|jinfo|jstat)\b", first_line)
    ):
        return "bash"

    return "text"


def recent_text_context(blocks: Sequence[str]) -> str:
    context_parts: list[str] = []
    for block in reversed(blocks):
        if parse_fenced_code_block(block) is not None:
            break
        if block.startswith("!["):
            continue
        context_parts.append(block)
        if len(context_parts) >= 2:
            break
    return "\n\n".join(reversed(context_parts))


def post_process_blocks(blocks: Sequence[str]) -> list[str]:
    out: list[str] = []
    i = 0
    while i < len(blocks):
        parsed = parse_fenced_code_block(blocks[i])
        if parsed is None:
            out.append(blocks[i])
            i += 1
            continue

        merged_lines = list(parsed[1])
        j = i + 1
        while j < len(blocks):
            next_parsed = parse_fenced_code_block(blocks[j])
            if next_parsed is None:
                break
            merged_lines.extend(next_parsed[1])
            j += 1

        language = infer_code_language(merged_lines, recent_text_context(out))
        out.append(make_fenced_code_block(merged_lines, language))
        i = j

    return out


def pdfminer_lines_for_block(
    page_height: float,
    block_bbox: tuple[float, float, float, float],
    page_pdfminer_lines: Sequence[TextLine],
) -> list[str]:
    x0, y0, x1, y1 = block_bbox
    pdf_y0 = page_height - y1 - PDFMINER_BLOCK_Y_MARGIN
    pdf_y1 = page_height - y0 + PDFMINER_BLOCK_Y_MARGIN
    pdf_x0 = x0 - PDFMINER_BLOCK_X_MARGIN
    pdf_x1 = x1 + PDFMINER_BLOCK_X_MARGIN

    matched: list[str] = []
    for line in page_pdfminer_lines:
        lx0, ly0, lx1, ly1 = line.bbox
        if ly1 < pdf_y0 or ly0 > pdf_y1:
            continue
        if lx1 < pdf_x0 or lx0 > pdf_x1:
            continue
        matched.append(line.text)
    return matched


def choose_best_text_lines(
    fitz_lines: Sequence[TextLine],
    pdfminer_lines: Sequence[str],
) -> list[str]:
    fitz_texts = [line.text for line in fitz_lines]
    if not fitz_texts or not pdfminer_lines:
        return fitz_texts

    merged: list[str] = []
    improved = False
    compatible = True
    for fitz_text, pdfminer_text in zip(fitz_texts, pdfminer_lines):
        fitz_norm = normalize_compare_text(fitz_text)
        pdfminer_norm = normalize_compare_text(pdfminer_text)
        if pdfminer_norm == fitz_norm:
            merged.append(fitz_text)
            continue
        if pdfminer_norm.startswith(fitz_norm) and len(pdfminer_norm) > len(fitz_norm) + 1:
            merged.append(pdfminer_text)
            improved = True
            continue
        compatible = False
        break

    if compatible and improved:
        if len(pdfminer_lines) > len(fitz_texts):
            merged.extend(pdfminer_lines[len(fitz_texts) :])
        return merged

    fitz_join = normalize_compare_text("\n".join(fitz_texts))
    pdfminer_join = normalize_compare_text("\n".join(pdfminer_lines))
    if fitz_join and pdfminer_join.startswith(fitz_join) and len(pdfminer_join) > len(fitz_join) + 1:
        return list(pdfminer_lines)
    if fitz_join and fitz_join in pdfminer_join and len(pdfminer_join) > len(fitz_join) + 30:
        return list(pdfminer_lines)

    return fitz_texts


def is_code_block(block: dict, lines: Sequence[str] | None = None) -> bool:
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
    text = block_text(block, lines)

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


def is_toc_block(lines: Sequence[str]) -> bool:
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


def markdown_for_text_block(block: dict, lines: Sequence[str]) -> str:
    if not lines:
        return ""

    if is_code_block(block, lines):
        return make_fenced_code_block(lines, "text")

    if is_toc_block(lines):
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


def convert(
    pdf_path: Path,
    output_md_path: Path,
    images_dir: Path,
    page_numbers: Sequence[int] | None = None,
) -> None:
    images_dir.mkdir(parents=True, exist_ok=True)
    output_md_path.parent.mkdir(parents=True, exist_ok=True)

    doc = fitz.open(pdf_path)
    selected_pages = (
        sorted(dict.fromkeys(page_numbers))
        if page_numbers is not None
        else list(range(1, doc.page_count + 1))
    )
    pdfminer_lines_by_page = extract_pdfminer_page_lines(pdf_path, selected_pages)
    pieces: list[str] = []
    image_prefix = Path(relpath(images_dir, output_md_path.parent)).as_posix()

    for page_number in selected_pages:
        page = doc[page_number - 1]
        page_dict = page.get_text("dict")
        blocks = sorted(page_dict["blocks"], key=block_sort_key)
        page_pdfminer_lines = pdfminer_lines_by_page.get(page_number, [])

        page_parts: list[str] = []
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

            fitz_lines = fitz_block_lines(block)
            if not fitz_lines:
                continue

            pdfminer_lines = pdfminer_lines_for_block(page.rect.height, block["bbox"], page_pdfminer_lines)
            text_lines = choose_best_text_lines(fitz_lines, pdfminer_lines)
            markdown = markdown_for_text_block(block, text_lines)
            if markdown:
                page_parts.append(markdown)

        pieces.extend(page_parts)

    processed = post_process_blocks(pieces)
    output_md_path.write_text("\n\n".join(processed).rstrip() + "\n", encoding="utf-8")


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
