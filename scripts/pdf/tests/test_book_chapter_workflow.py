from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
VERIFY_MODULE_PATH = ROOT / "scripts/pdf/verify_pdf_md_integrity.py"
SPLIT_MODULE_PATH = ROOT / "scripts/pdf/generate_jvm_book_chapters.py"


def load_module(name: str, module_path: Path):
    spec = importlib.util.spec_from_file_location(name, module_path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class BookChapterWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.verify = load_module("verify_pdf_md_integrity", VERIFY_MODULE_PATH)
        cls.splitter = load_module("generate_jvm_book_chapters", SPLIT_MODULE_PATH)

    def test_markdown_inputs_defaults_to_chapters_excluding_readme(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            chapters_dir = tmp / "chapters"
            chapters_dir.mkdir()
            (chapters_dir / "README.md").write_text("# index\n", encoding="utf-8")
            (chapters_dir / "00-前言.md").write_text("## 前言\n", encoding="utf-8")
            (chapters_dir / "01-第1章.md").write_text("## 第1章\n", encoding="utf-8")

            paths = self.verify.markdown_inputs(md_path=None, chapters_dir=chapters_dir)

            self.assertEqual(
                paths,
                [
                    chapters_dir / "00-前言.md",
                    chapters_dir / "01-第1章.md",
                ],
            )

    def test_collect_markdown_text_joins_chapters_in_order(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            paths = []
            for name, body in [
                ("00-前言.md", "## 前言\n\n第一段\n"),
                ("01-第1章.md", "## 第1章\n\n第二段\n"),
            ]:
                path = tmp / name
                path.write_text(body, encoding="utf-8")
                paths.append(path)

            text = self.verify.collect_markdown_text(paths)

            self.assertIn("## 前言\n\n第一段", text)
            self.assertIn("## 第1章\n\n第二段", text)
            self.assertLess(text.index("第一段"), text.index("第二段"))

    def test_split_main_markdown_uses_last_heading_and_rewrites_image_paths(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            chapters_dir = tmp / "chapters"
            chapters_dir.mkdir()
            chapter_path = chapters_dir / "03-第1章-走近Java.md"
            chapter_path.write_text("## 第1章　走近Java\n旧内容\n", encoding="utf-8")

            main_text = "\n".join(
                [
                    "# 目录",
                    "## 第1章　走近Java",
                    "目录项",
                    "## 第1章　走近Java",
                    "正文",
                    "![图](images/page-0001-image-01.jpeg)",
                    "",
                ]
            )

            chunks = self.splitter.split_main_markdown_into_chapters(main_text, [chapter_path])

            self.assertEqual(
                chunks[chapter_path],
                "## 第1章　走近Java\n正文\n![图](../images/page-0001-image-01.jpeg)\n",
            )

    def test_split_main_markdown_rewrites_temp_relative_image_paths(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            chapters_dir = tmp / "chapters"
            chapters_dir.mkdir()
            chapter_path = chapters_dir / "03-第1章-走近Java.md"
            chapter_path.write_text("## 第1章　走近Java\n旧内容\n", encoding="utf-8")

            main_text = "\n".join(
                [
                    "## 第1章　走近Java",
                    "正文",
                    "![图](../../home/feng/code/learning/java-learning/references/深入理解Java虚拟机_JVM高级特性与最佳实践_第3版/images/page-0056-image-01.jpeg)",
                    "",
                ]
            )

            chunks = self.splitter.split_main_markdown_into_chapters(main_text, [chapter_path])

            self.assertEqual(
                chunks[chapter_path],
                "## 第1章　走近Java\n正文\n![图](../images/page-0056-image-01.jpeg)\n",
            )

    def test_filter_image_names_by_page_from_ignores_frontmatter_images(self) -> None:
        names = [
            "page-0001-image-01.jpeg",
            "page-0002-image-01.jpeg",
            "page-0003-image-01.jpeg",
        ]

        filtered = self.verify.filter_image_names_by_page_from(names, page_from=3)

        self.assertEqual(filtered, ["page-0003-image-01.jpeg"])

    def test_broken_markdown_image_refs_checks_resolved_paths(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            chapters_dir = tmp / "chapters"
            chapters_dir.mkdir()
            images_dir = tmp / "images"
            images_dir.mkdir()
            (images_dir / "page-0001-image-01.jpeg").write_bytes(b"x")

            chapter = chapters_dir / "03-第1章.md"
            chapter.write_text(
                "\n".join(
                    [
                        "## 第1章",
                        "![ok](../images/page-0001-image-01.jpeg)",
                        "![bad](../../home/feng/code/learning/java-learning/references/book/images/page-0002-image-01.jpeg)",
                        "",
                    ]
                ),
                encoding="utf-8",
            )

            broken = self.verify.broken_markdown_image_refs([chapter])

            self.assertEqual(
                broken,
                [
                    (
                        chapter,
                        "../../home/feng/code/learning/java-learning/references/book/images/page-0002-image-01.jpeg",
                    )
                ],
            )


if __name__ == "__main__":
    unittest.main()
