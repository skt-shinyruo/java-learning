# PDF 转 Markdown 工具

这个目录保存了把 PDF 转成 Markdown 以及校验 Markdown 与 PDF 内容完整性的脚本。

说明：
- 这些脚本不追求 1:1 还原 PDF 的字体、字距、分页、自动换行等排版细节。
- 重点保证内容不缺失：页码覆盖、图片不丢失不串页、文本不漏段。

## 依赖

脚本依赖 `PyMuPDF`（模块名 `fitz`）。

推荐用 `uv` 临时安装运行（避免污染项目环境）：

```bash
UV_CACHE_DIR=/tmp/uv-cache uv run --with PyMuPDF python scripts/pdf/verify_pdf_md_integrity.py
```

## 脚本

- `convert_pdf_to_markdown.py`
  - PDF -> Markdown（按页输出 `<!-- page #### -->`，并导出内嵌图片）
- `fix_footnote_spacing.py`
  - 修复脚注排版：把同一行的多条脚注拆行、去掉脚注项之间的空行、补齐 `[n]` 后缺失的空格等
- `verify_pdf_md_integrity.py`
  - 完整性校验：页码标记覆盖、图片引用/存在性/是否放在对应页段、文本抽样覆盖率扫描

