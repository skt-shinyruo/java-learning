#!/usr/bin/env python3
"""
Integrity checks for PDF -> Markdown conversion.

Focus:
- No missing pages (Markdown page markers cover every PDF page).
- No missing / broken / misplaced images.
- Best-effort detection of missing text by sampling PDF-extracted lines and checking
  if they exist in the corresponding Markdown page segment.

This does not try to reproduce PDF typography or exact line breaks.
"""

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path


PAGE_MARKER_RE = re.compile(r"^<!-- page (\d{4}) -->\s*$")
MD_IMAGE_RE = re.compile(r"!\[[^\]]*\]\(([^)]+)\)")
PAGE_IMAGE_NAME_RE = re.compile(
    r"(?:^|/)page-(\d{4})-image-(\d+)\.(?:jpe?g|png)$", re.IGNORECASE
)
CODE_FENCE_RE = re.compile(r"^```")
CJK_RE = re.compile(r"[\u4e00-\u9fff]")


@dataclass(frozen=True)
class PageSegment:
    page: int
    text: str


def parse_page_segments(md_path: Path) -> dict[int, PageSegment]:
    lines = md_path.read_text(encoding="utf-8").splitlines(True)
    segments: dict[int, PageSegment] = {}
    current_page: int | None = None
    buf: list[str] = []

    for line in lines:
        m = PAGE_MARKER_RE.match(line.strip())
        if m:
            if current_page is not None:
                segments[current_page] = PageSegment(page=current_page, text="".join(buf))
            current_page = int(m.group(1))
            buf = []
            continue

        if current_page is not None:
            buf.append(line)

    if current_page is not None:
        segments[current_page] = PageSegment(page=current_page, text="".join(buf))

    return segments


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

        # Drop images (their contents exist as pixels, not text).
        line = re.sub(r"!\[[^\]]*\]\([^)]+\)", "", line)
        # Keep forced line breaks as nothing.
        line = line.replace("<br>", "")
        # Strip headings.
        line = re.sub(r"^\s*#+\s*", "", line)
        # Keep visible text of links plus URL (URLs often appear in PDF footnotes).
        line = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r"\1\2", line)
        out.append(line)

    return re.sub(r"\s+", "", "".join(out))


def normalize_pdf_line(line: str) -> str:
    return re.sub(r"\s+", "", line)


def choose_evenly(items: list[str], n: int) -> list[str]:
    if len(items) <= n:
        return items
    # Even spacing across the list.
    step = (len(items) - 1) / (n - 1)
    idxs = [round(i * step) for i in range(n)]
    out: list[str] = []
    for idx in idxs:
        out.append(items[int(idx)])
    return out


def build_pdf_candidate_lines(pdf_text: str) -> list[str]:
    lines = [ln.strip() for ln in pdf_text.splitlines()]
    lines = [ln for ln in lines if ln]
    if len(lines) >= 6:
        # Running headers/footers vary and often don't exist in MD.
        lines = lines[2:-2]

    candidates: list[str] = []
    for ln in lines:
        if not CJK_RE.search(ln):
            continue
        norm = normalize_pdf_line(ln)
        if len(norm) < 12:
            continue
        # Prefer sentence-like content and footnotes; avoid sparse diagram labels.
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
        default=Path(
            "references/深入理解Java虚拟机_JVM高级特性与最佳实践_第3版/"
            "深入理解Java虚拟机_JVM高级特性与最佳实践_第3版.md"
        ),
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
    args = ap.parse_args()

    # Lazy import so running without PyMuPDF is still possible (though checks will fail).
    import fitz  # type: ignore

    doc = fitz.open(args.pdf.as_posix())
    pdf_pages = doc.page_count
    segments = parse_page_segments(args.md)
    marker_pages = sorted(segments.keys())

    print(f"pdf_pages {pdf_pages}")
    print(f"md_page_markers {len(marker_pages)}")
    if marker_pages:
        print(f"md_marker_first_last {marker_pages[0]:04d} {marker_pages[-1]:04d}")

    expected_pages = set(range(1, pdf_pages + 1))
    missing_markers = sorted(expected_pages - set(marker_pages))
    extra_markers = sorted(set(marker_pages) - expected_pages)
    if missing_markers:
        print(f"ERROR missing_page_markers {len(missing_markers)} sample={missing_markers[:10]}")
    if extra_markers:
        print(f"WARN extra_page_markers {len(extra_markers)} sample={extra_markers[:10]}")

    # Image existence and reference checks.
    imgs = image_files(args.images)
    print(f"image_files {len(imgs)}")

    md_text = args.md.read_text(encoding="utf-8")
    md_img_links = [m.group(1) for m in MD_IMAGE_RE.finditer(md_text)]
    md_img_names = [Path(x).name for x in md_img_links]
    md_img_names_set = set(md_img_names)

    referenced_missing = sorted(md_img_names_set - imgs)
    unused = sorted(imgs - md_img_names_set)
    if referenced_missing:
        print(f"ERROR referenced_but_missing_images {len(referenced_missing)} sample={referenced_missing[:10]}")
    if unused:
        print(f"WARN unused_images {len(unused)} sample={unused[:10]}")

    # Misplaced images: referenced in a different page segment than their filename page.
    misplaced: list[tuple[int, str]] = []
    for page, seg in segments.items():
        for link in MD_IMAGE_RE.findall(seg.text):
            m = PAGE_IMAGE_NAME_RE.search(link)
            if not m:
                continue
            img_page = int(m.group(1))
            if img_page != page:
                misplaced.append((page, Path(link).name))
    if misplaced:
        print(f"ERROR misplaced_images {len(misplaced)} sample={misplaced[:10]}")

    # Text coverage check (best effort).
    warnings: list[str] = []
    scored = 0
    for i in range(pdf_pages):
        page_no = i + 1
        seg = segments.get(page_no)
        if seg is None:
            continue
        pdf_text = doc.load_page(i).get_text("text")
        candidates = build_pdf_candidate_lines(pdf_text)
        if len(candidates) < args.min_candidates:
            continue

        md_norm = normalize_md_text(seg.text)
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
    if missing_markers:
        ok = False
    if referenced_missing:
        ok = False
    if misplaced:
        ok = False
    if warnings:
        ok = False

    return 0 if ok else 2


if __name__ == "__main__":
    raise SystemExit(main())

