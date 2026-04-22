#!/usr/bin/env python3
"""
Integrity checks for PDF -> Markdown conversion.

Focus:
- No missing / broken image references.
- Best-effort detection of missing text by sampling PDF-extracted lines and checking
  if they exist anywhere in the Markdown document.

This does not try to reproduce PDF typography or exact line breaks.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path
from typing import Sequence


MD_IMAGE_RE = re.compile(r"!\[[^\]]*\]\(([^)]+)\)")
PAGE_IMAGE_NAME_RE = re.compile(
    r"(?:^|/)page-(\d{4})-image-(\d+)\.(?:jpe?g|png)$", re.IGNORECASE
)
CODE_FENCE_RE = re.compile(r"^```")
CJK_RE = re.compile(r"[\u4e00-\u9fff]")


def normalize_md_text(text: str) -> str:
    lines = text.splitlines()
    out: list[str] = []
    in_code_block = False
    for line in lines:
        if CODE_FENCE_RE.match(line):
            in_code_block = not in_code_block
            continue
        if in_code_block:
            out.append(line)
            continue

        line = re.sub(r"!\[[^\]]*\]\([^)]+\)", "", line)
        line = line.replace("<br>", "")
        line = re.sub(r"^\s*#+\s*", "", line)
        line = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r"\1\2", line)
        out.append(line)

    return re.sub(r"\s+", "", "".join(out))


def normalize_pdf_line(line: str) -> str:
    return re.sub(r"\s+", "", line)


def choose_evenly(items: list[str], n: int) -> list[str]:
    if len(items) <= n:
        return items
    step = (len(items) - 1) / (n - 1)
    idxs = [round(i * step) for i in range(n)]
    return [items[int(idx)] for idx in idxs]


def build_pdf_candidate_lines(pdf_text: str) -> list[str]:
    lines = [ln.strip() for ln in pdf_text.splitlines()]
    lines = [ln for ln in lines if ln]
    if len(lines) >= 6:
        lines = lines[2:-2]

    candidates: list[str] = []
    for ln in lines:
        if not CJK_RE.search(ln):
            continue
        norm = normalize_pdf_line(ln)
        if len(norm) < 12:
            continue
        if any(ch in ln for ch in ["。", "，", "：", "；", "？", "！"]) or ln.startswith("["):
            candidates.append(ln)
    return candidates


def image_files(images_dir: Path) -> set[str]:
    if not images_dir.exists():
        return set()
    out: set[str] = set()
    for p in images_dir.iterdir():
        if not p.is_file():
            continue
        if p.suffix.lower() not in {".jpg", ".jpeg", ".png"}:
            continue
        out.add(p.name)
    return out


def page_number_from_image_name(name: str) -> int | None:
    match = PAGE_IMAGE_NAME_RE.search(name)
    if match is None:
        return None
    return int(match.group(1))


def filter_image_names_by_page_from(names: Sequence[str], page_from: int) -> list[str]:
    out: list[str] = []
    for name in names:
        page_no = page_number_from_image_name(name)
        if page_no is not None and page_no < page_from:
            continue
        out.append(name)
    return out


def markdown_inputs(md_path: Path | None, chapters_dir: Path) -> list[Path]:
    if md_path is not None:
        return [md_path]
    return [p for p in sorted(chapters_dir.glob("*.md")) if p.name != "README.md"]


def collect_markdown_text(paths: Sequence[Path]) -> str:
    return "\n\n".join(path.read_text(encoding="utf-8") for path in paths)


def broken_markdown_image_refs(paths: Sequence[Path]) -> list[tuple[Path, str]]:
    broken: list[tuple[Path, str]] = []
    for path in paths:
        text = path.read_text(encoding="utf-8")
        for match in MD_IMAGE_RE.finditer(text):
            target = match.group(1)
            if re.match(r"^[a-z]+://", target, re.IGNORECASE) or target.startswith("#"):
                continue
            resolved = (path.parent / target).resolve()
            if not resolved.exists():
                broken.append((path, target))
    return broken


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--pdf",
        type=Path,
        default=Path("references/深入理解Java虚拟机：JVM高级特性与最佳实践（第3版）.pdf"),
    )
    ap.add_argument(
        "--md",
        type=Path,
        default=None,
    )
    ap.add_argument(
        "--chapters-dir",
        type=Path,
        default=Path("references/深入理解Java虚拟机_JVM高级特性与最佳实践_第3版/chapters"),
    )
    ap.add_argument(
        "--images",
        type=Path,
        default=Path("references/深入理解Java虚拟机_JVM高级特性与最佳实践_第3版/images"),
    )
    ap.add_argument("--sample-lines", type=int, default=10)
    ap.add_argument("--min-candidates", type=int, default=3)
    ap.add_argument("--min-coverage", type=float, default=0.7)
    ap.add_argument("--max-warnings", type=int, default=50)
    ap.add_argument(
        "--page-from",
        type=int,
        default=3,
        help="Only verify PDF pages starting from this 1-based page number. Chapter-only outputs skip front matter.",
    )
    args = ap.parse_args()

    import fitz  # type: ignore

    doc = fitz.open(args.pdf.as_posix())
    pdf_pages = doc.page_count
    print(f"pdf_pages {pdf_pages}")

    imgs = image_files(args.images)
    print(f"image_files {len(imgs)}")

    md_paths = markdown_inputs(args.md, args.chapters_dir)
    md_text = collect_markdown_text(md_paths)
    broken_refs = broken_markdown_image_refs(md_paths)
    md_img_links = [m.group(1) for m in MD_IMAGE_RE.finditer(md_text)]
    md_img_names = filter_image_names_by_page_from([Path(x).name for x in md_img_links], args.page_from)
    md_img_names_set = set(md_img_names)

    relevant_imgs = set(filter_image_names_by_page_from(sorted(imgs), args.page_from))
    referenced_missing = sorted(md_img_names_set - relevant_imgs)
    unused = sorted(relevant_imgs - md_img_names_set)
    if referenced_missing:
        print(f"ERROR referenced_but_missing_images {len(referenced_missing)} sample={referenced_missing[:10]}")
    if broken_refs:
        sample = [(path.as_posix(), target) for path, target in broken_refs[:10]]
        print(f"ERROR broken_markdown_image_refs {len(broken_refs)} sample={sample}")
    if unused:
        print(f"WARN unused_images {len(unused)} sample={unused[:10]}")

    image_refs = [PAGE_IMAGE_NAME_RE.search(link) for link in md_img_links]
    image_pages = [int(m.group(1)) for m in image_refs if m]
    if image_pages:
        is_sorted = image_pages == sorted(image_pages)
        print(f"image_page_order_sorted {is_sorted}")
        if not is_sorted:
            print(f"ERROR image_page_order sample={image_pages[:20]}")

    md_norm = normalize_md_text(md_text)

    warnings: list[str] = []
    scored = 0
    for i in range(args.page_from - 1, pdf_pages):
        page_no = i + 1
        pdf_text = doc.load_page(i).get_text("text")
        candidates = build_pdf_candidate_lines(pdf_text)
        if len(candidates) < args.min_candidates:
            continue

        sample = choose_evenly(candidates, args.sample_lines)
        sample_norm = [normalize_pdf_line(x) for x in sample]

        hits = sum(1 for s in sample_norm if s and (s in md_norm))
        cov = hits / max(len(sample_norm), 1)
        scored += 1
        if cov < args.min_coverage:
            warnings.append(
                f"WARN low_text_coverage page={page_no:04d} cov={cov:.3f} samples={len(sample_norm)}"
            )

    print(f"text_scored_pages {scored}")
    print(f"low_coverage_pages {len(warnings)}")
    for line in warnings[: args.max_warnings]:
        print(line)

    ok = True
    if referenced_missing:
        ok = False
    if broken_refs:
        ok = False
    if warnings:
        ok = False
    if image_pages and image_pages != sorted(image_pages):
        ok = False

    return 0 if ok else 2


if __name__ == "__main__":
    raise SystemExit(main())
