# PDF 转 Markdown 工具

这个目录保存了把 PDF 转成 Markdown 以及校验 Markdown 与 PDF 内容完整性的脚本。

说明：
- 这些脚本不追求 1:1 还原 PDF 的字体、字距、分页、自动换行等排版细节。
- 重点保证内容不缺失：图片不丢失不乱序、文本不漏段。
- 当前这本《深入理解Java虚拟机》只保留按章节拆分的 Markdown 产物，不再保留整本合并后的 Markdown 文件。

## 依赖

脚本依赖：

- `PyMuPDF`（模块名 `fitz`），用于按页遍历版面和导出内嵌图片。
- `pdfminer.six`，用于补全 `PyMuPDF` 在少数超长技术行上的截断文本。

推荐用 `uv` 临时安装运行（避免污染项目环境）：

```bash
UV_CACHE_DIR=/tmp/uv-cache uv run --with PyMuPDF --with pdfminer.six python scripts/pdf/verify_pdf_md_integrity.py
```

## 脚本

- `convert_pdf_to_markdown.py`
  - PDF -> Markdown（底层转换器；导出内嵌图片，并在生成后合并被 PDF 分块/分页打断的连续代码块，推断更合适的 fenced code language）
- `generate_jvm_book_chapters.py`
  - 为《深入理解Java虚拟机》生成章节版 Markdown；内部会临时生成整本 Markdown 再按既有章节标题切分，但不会保留整本 Markdown 文件
- `fix_footnote_spacing.py`
  - 修复章节 Markdown 的脚注排版：把同一行的多条脚注拆行、去掉脚注项之间的空行、补齐 `[n]` 后缺失的空格等
- `verify_pdf_md_integrity.py`
  - 完整性校验：默认聚合所有章节 Markdown 做图片引用/存在性/顺序检查、全文文本抽样覆盖率扫描
