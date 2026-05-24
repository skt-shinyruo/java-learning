#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parents[2]
CHAPTERS_DIR = ROOT_DIR / "references/深入理解Java虚拟机_JVM高级特性与最佳实践_第3版/chapters"
SPLIT_PARAGRAPH_END = re.compile(r"[A-Za-z_][A-Za-z0-9_]*$")
SPLIT_PARAGRAPH_START = re.compile(r"^[a-z0-9=({\[]")
RAW_NUMERIC_REFERENCE = re.compile(r"\[(\d+)\]")
RAW_NUMERIC_DEFINITION = re.compile(r"^\[(\d+)\]\s+")
FOOTNOTE_REFERENCE = re.compile(r"\[\^[^\]]+\]")

CHECKS = {
    "plain_heading_lines": re.compile(
        r"^(本书面向的读者|如何阅读本书|语言约定|内容特色与更新|参考资料|联系作者|周志明|A\.\d+　.+)$"
    ),
    "preface_chapter_summary_heading": re.compile(
        r"^## 第\d+章　(?:介绍了|分享了|讲解了|分析了|通过)"
    ),
    "prose_heading_lines": re.compile(
        r"^#{2,6} (?:\d+(?:\.\d+)+(?:、\d+(?:\.\d+)*)*节\S|第\d+章(?:至第\d+章)?\S)"
    ),
    "caption_footnote_glued": re.compile(
        r"^(?:图\d+-\d+|表\d+-\d+|代码清单\d+-\d+)[　 ].*\[\d+\]\s*\S"
    ),
    "isolated_inline_reference": re.compile(r"^\[\d+\]$"),
    "footnote_missing_space": re.compile(r"^\[\d+\]\S"),
    "jvm_option_fullwidth_colon": re.compile(r"(?:[-+]?XX|-Xloggc|-Xlog|-X-log|-g|-verbose)："),
    "jvm_option_bad_prefix": re.compile(r"(?<![-A-Za-z0-9])\+XX:"),
    "split_jvm_option_line": re.compile(r"-XX:$"),
    "known_bad_hyphen": re.compile(
        r"(Non-Con-servative|Techno-logies|Hot-Spot|Shen-andoah|OutOf-MemoryError|"
        r"MaxMeta-spaceSize|under-standingjvm|MethodParam-eters|RuntimeVisibleParam-eterAnnotations|"
        r"RuntimeInvisibleParam-eterAnnotations|CONSTANT_Method-Handle_info|"
        r"CONSTANT_Method-Type_info|Con-stantValue|ane-warray|invoke-special|"
        r"get-System-ClassLoader|FailOverToOld-Verifier|StackMap-Table|PrintIdeal-GraphFile|"
        r"slowde-bug|Re-ference|boot-jd、|Kloc-work|Integer-Cache|"
        r"Tenuring-Threshold|Survivor-Ratio|PrintGCDate-Stamps|"
        r"Print-GCApplicationConcurrentTime|PrintAdaptive-SizePolicy|"
        r"PrintTenuring-Distribution|StackOver-flowError|String-Builder|"
        r"Meta-space|InputFile-Object|Red-naxelaFX|descrip-tor|argu-ments|"
        r"RuntimeInvisibleParameter-Annotations|Manage-ment|JCon-sole|getPro-perties|"
        r"Net-Beans|Eclipst|Con-current|CMSInitiatingOccu-pancyFraction|"
        r"UseCMS-CompactAtFullCollection|CMSFullGCsBefore-Compaction|Open-JDK|"
        r"Win-dows|alloca-tion|JavacProcessing-Environment|JBoss-Cache|Always-Tenure|"
        r"Tech-nology|UnlockExperimentalVMOptions-XX|X-log)"
    ),
    "broken_package_space": re.compile(r"org\.fenixsoft\.oom\. JavaVMStackSOF"),
    "known_split_paragraph_line": re.compile(
        r"^(专门阐述各个版本间的差异。|了目前仍然在实验室状态的Java协程的相关内容。|"
        r"就可能出现错误，而volatile关键字则可以避免此类情况的发生\[2\]。|"
        r"使用\[3\]，|线程改变对象引用关系时，|象的地址，|——Brooks Pointer。|"
        r"指针的功劳，|量回收。|有对象的，|的“Critical Throughput”的话\[16\]，|"
        r"不需要自动装箱和拆箱，|值。)"
    ),
}


def iter_chapter_lines():
    for path in sorted(CHAPTERS_DIR.glob("*.md")):
        if path.name == "README.md":
            continue
        in_code = False
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if line.startswith("```"):
                in_code = not in_code
            yield path, line_number, line


def iter_noncode_lines():
    for path in sorted(CHAPTERS_DIR.glob("*.md")):
        if path.name == "README.md":
            continue
        in_code = False
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if line.startswith("```"):
                in_code = not in_code
                continue
            if in_code:
                continue
            yield path, line_number, line


def main() -> int:
    failed = False
    for name, pattern in CHECKS.items():
        hits = [
            (path, line_number, line)
            for path, line_number, line in iter_chapter_lines()
            if pattern.search(line)
        ]
        if not hits:
            continue

        failed = True
        print(f"{name}: {len(hits)} issue(s)")
        for path, line_number, line in hits[:20]:
            rel = path.relative_to(ROOT_DIR)
            print(f"  {rel}:{line_number}: {line[:180]}")

    blank_hits = []
    for path in sorted(CHAPTERS_DIR.glob("*.md")):
        if path.name == "README.md":
            continue
        in_code = False
        consecutive_blank_lines = 0
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if line.startswith("```"):
                in_code = not in_code
            if in_code:
                consecutive_blank_lines = 0
                continue
            if line == "":
                consecutive_blank_lines += 1
                if consecutive_blank_lines >= 2:
                    blank_hits.append((path, line_number, "multiple consecutive blank lines"))
            else:
                consecutive_blank_lines = 0

    if blank_hits:
        failed = True
        print(f"multiple_blank_lines: {len(blank_hits)} issue(s)")
        for path, line_number, line in blank_hits[:20]:
            rel = path.relative_to(ROOT_DIR)
            print(f"  {rel}:{line_number}: {line}")

    split_paragraph_hits = []
    for path in sorted(CHAPTERS_DIR.glob("*.md")):
        if path.name == "README.md":
            continue
        in_code = False
        previous_nonblank_line = None
        previous_nonblank_line_number = 0
        saw_blank_line = False
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if line.startswith("```"):
                in_code = not in_code
                saw_blank_line = False
                continue
            if in_code:
                continue
            if line == "":
                saw_blank_line = True
                continue
            if (
                saw_blank_line
                and previous_nonblank_line is not None
                and SPLIT_PARAGRAPH_END.search(previous_nonblank_line)
                and SPLIT_PARAGRAPH_START.search(line)
            ):
                split_paragraph_hits.append(
                    (path, previous_nonblank_line_number, previous_nonblank_line, line_number, line)
                )
            saw_blank_line = False
            previous_nonblank_line = line
            previous_nonblank_line_number = line_number

    if split_paragraph_hits:
        failed = True
        print(f"split_paragraph_fragment: {len(split_paragraph_hits)} issue(s)")
        for path, previous_line_number, previous_line, line_number, line in split_paragraph_hits[:20]:
            rel = path.relative_to(ROOT_DIR)
            print(
                f"  {rel}:{previous_line_number}->{line_number}: "
                f"{previous_line[:120]} / {line[:120]}"
            )

    raw_numeric_reference_hits = []
    raw_numeric_definition_hits = []
    for path, line_number, line in iter_noncode_lines():
        if RAW_NUMERIC_DEFINITION.match(line):
            raw_numeric_definition_hits.append((path, line_number, line))
            continue
        for match in RAW_NUMERIC_REFERENCE.finditer(line):
            start = match.start()
            if start > 0 and line[start - 1] in ("!", "^"):
                continue
            raw_numeric_reference_hits.append((path, line_number, line))
            break

    if raw_numeric_reference_hits:
        failed = True
        print(f"raw_numeric_footnote_reference: {len(raw_numeric_reference_hits)} issue(s)")
        for path, line_number, line in raw_numeric_reference_hits[:20]:
            rel = path.relative_to(ROOT_DIR)
            print(f"  {rel}:{line_number}: {line[:180]}")

    if raw_numeric_definition_hits:
        failed = True
        print(f"raw_numeric_footnote_definition: {len(raw_numeric_definition_hits)} issue(s)")
        for path, line_number, line in raw_numeric_definition_hits[:20]:
            rel = path.relative_to(ROOT_DIR)
            print(f"  {rel}:{line_number}: {line[:180]}")

    code_footnote_hits = []
    for path in sorted(CHAPTERS_DIR.glob("*.md")):
        if path.name == "README.md":
            continue
        in_code = False
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if line.startswith("```"):
                in_code = not in_code
                continue
            if in_code and FOOTNOTE_REFERENCE.search(line):
                code_footnote_hits.append((path, line_number, line))

    if code_footnote_hits:
        failed = True
        print(f"code_block_footnote_reference: {len(code_footnote_hits)} issue(s)")
        for path, line_number, line in code_footnote_hits[:20]:
            rel = path.relative_to(ROOT_DIR)
            print(f"  {rel}:{line_number}: {line[:180]}")

    if failed:
        return 1

    print("JVM book reference format checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
