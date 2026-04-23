from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
BOOK_DIR = ROOT / "references/深入理解Java虚拟机_JVM高级特性与最佳实践_第3版"
BOOK_PDF = BOOK_DIR / "深入理解Java虚拟机：JVM高级特性与最佳实践（第3版）.pdf"
MODULE_PATH = ROOT / "scripts/pdf/convert_pdf_to_markdown.py"


def load_module():
    spec = importlib.util.spec_from_file_location("convert_pdf_to_markdown", MODULE_PATH)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class ConvertPdfToMarkdownRegressionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.module = load_module()

    def render_page(self, page_number: int) -> str:
        return self.render_pages([page_number])

    def render_pages(self, page_numbers: list[int]) -> str:
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            output = tmp / "page.md"
            images_dir = tmp / "images"
            self.module.convert(
                BOOK_PDF,
                output,
                images_dir,
                page_numbers=page_numbers,
            )
            return output.read_text(encoding="utf-8")

    def test_page_0175_keeps_full_gc_tag_list(self) -> None:
        text = self.render_page(175)
        self.assertIn("bytecode，census，class，classhisto", text)

    def test_page_0177_keeps_full_heap_addresses_and_safepoint_text(self) -> None:
        text = self.render_page(177)
        self.assertIn("0xfffffffe50500800", text)
        self.assertIn("Stopping threads took:", text)
        self.assertIn("Initial Refinement Zones: green: 23, yellow:", text)
        self.assertIn("collection set length 24", text)

    def test_page_0191_keeps_full_gc_log_line(self) -> None:
        text = self.render_page(191)
        self.assertIn("10474K->4244K(19456K), [Perm : 2104K->2104K(12288K)]", text)

    def test_page_0266_keeps_full_reference_gc_log(self) -> None:
        text = self.render_page(266)
        self.assertIn("WeakReference, 4072 refs, 0.0012099 secs", text)
        self.assertIn("JNI Weak Reference, 0.0994015 secs", text)

    def test_page_0283_keeps_full_full_gc_records(self) -> None:
        text = self.render_page(283)
        self.assertIn("12287K->12287K(12288K)], 0.0544163 secs", text)
        self.assertIn("41779K->27347K(42056K), 0.0954341 secs", text)

    def test_pages_0082_0083_merge_cross_page_cpp_listing(self) -> None:
        text = self.render_pages([82, 83])
        self.assertEqual(text.count("```cpp"), 1)
        self.assertIn("SET_STACK_OBJECT(result, 0);", text)
        self.assertIn("UPDATE_PC_AND_TOS_AND_CONTINUE(3, 1);", text)
        between = text.split("SET_STACK_OBJECT(result, 0);", 1)[1].split(
            "UPDATE_PC_AND_TOS_AND_CONTINUE(3, 1);", 1
        )[0]
        self.assertNotIn("<!-- page 0083 -->", between)

    def test_page_0106_merges_java_listing_into_one_block(self) -> None:
        text = self.render_page(106)
        self.assertEqual(text.count("```java"), 1)
        self.assertEqual(text.count("```"), 2)
        self.assertIn("public class ReferenceCountingGC {", text)
        self.assertIn("System.gc();", text)

    def test_page_0084_infers_cpp_from_contextual_filename(self) -> None:
        text = self.render_page(84)
        self.assertIn("```cpp", text)
        self.assertIn("// Bit-format of an object header", text)

    def test_page_0107_keeps_runtime_output_as_text(self) -> None:
        text = self.render_page(107)
        self.assertIn("```text", text)
        self.assertNotIn("```cpp", text)

    def test_page_0059_classifies_linux_install_commands_as_bash(self) -> None:
        text = self.render_page(59)
        self.assertIn("```bash", text)
        self.assertIn("sudo apt-get install build-essential", text)
        self.assertIn("sudo apt-get install openjdk-11-jdk", text)

    def test_page_0061_classifies_configure_commands_as_bash(self) -> None:
        text = self.render_page(61)
        self.assertIn("```bash", text)
        self.assertIn("bash configure [options]", text)
        self.assertIn("bash configure --enable-debug --with-jvm-variants=server", text)

    def test_page_0270_classifies_eclipse_ini_as_ini(self) -> None:
        text = self.render_page(270)
        self.assertIn("```ini", text)
        self.assertIn("-vmargs", text)
        self.assertIn("-Dcom.sun.management.jmxremote", text)

    def test_page_0280_classifies_windows_console_examples_as_bat(self) -> None:
        text = self.render_page(280)
        self.assertIn("```bat", text)
        self.assertIn(r"C:\Users\IcyFenix>jps", text)
        self.assertIn(r"C:\Users\IcyFenix>jstat -class 6372", text)

    def test_infer_java_for_member_level_snippet(self) -> None:
        language = self.module.infer_code_language(
            [
                "volatile boolean shutdownRequested;",
                "public void shutdown() {",
                "    shutdownRequested = true;",
                "}",
                "public void doWork() {",
                "    while (!shutdownRequested) {",
                "        // 代码的业务逻辑",
                "    }",
                "}",
            ],
            "代码清单12-3　volatile的使用场景",
        )
        self.assertEqual(language, "java")

    def test_infer_java_for_field_visibility_example(self) -> None:
        language = self.module.infer_code_language(
            [
                "public static final int i;",
                "public final int j;",
                "static {",
                "    i = 0;",
                "}",
                "{",
                "    j = 0;",
                "}",
            ],
            "代码清单12-7　final与可见性",
        )
        self.assertEqual(language, "java")

    def test_infer_cpp_for_pointer_function_snippet(self) -> None:
        language = self.module.infer_code_language(
            [
                "void oop_field_store(oop* field, oop new_value) {",
                "    *field = new_value;",
                "    post_write_barrier(field, new_value);",
                "}",
            ],
            "代码清单3-6　写后屏障更新卡表",
        )
        self.assertEqual(language, "cpp")

    def test_infer_text_for_javap_bytecode_listing(self) -> None:
        language = self.module.infer_code_language(
            [
                "public static void increase();",
                "    Code:",
                "        Stack=2, Locals=0, Args_size=0",
                "        0:   getstatic       #13; //Field race:I",
                "        3:   iconst_1",
                "        4:   iadd",
                "        5:   putstatic       #13; //Field race:I",
                "        8:   return",
                "    LineNumberTable:",
                "        line 14: 0",
            ],
            "代码清单12-2　VolatileTest的字节码",
        )
        self.assertEqual(language, "text")

    def test_infer_java_when_context_mentions_bytecode_but_caption_is_source(self) -> None:
        language = self.module.infer_code_language(
            [
                "public static void main(String[] args) {",
                "    if (true) {",
                '        System.out.println("block 1");',
                "    } else {",
                '        System.out.println("block 2");',
                "    }",
                "}",
            ],
            (
                "Java语言当然也可以进行条件编译，方法就是使用条件为常量的if语句。"
                "生成的字节码之中只包括一条输出语句。\n\n"
                "代码清单10-14　Java语言的条件编译"
            ),
        )
        self.assertEqual(language, "java")

    def test_rendered_markdown_omits_page_markers(self) -> None:
        text = self.render_pages([82, 83, 106])
        self.assertNotIn("<!-- page ", text)


if __name__ == "__main__":
    unittest.main()
