#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUTPUT_HTML="$ROOT_DIR/mkdocs/site/redis/content/hyperloglog/index.html"

cd "$ROOT_DIR"

mkdocs build -f mkdocs/mkdocs.yml >/dev/null

test -f "$OUTPUT_HTML"

if grep -Fq '$\frac{1}{2}$' "$OUTPUT_HTML"; then
  echo "Found raw inline LaTeX in generated HTML: expected MathJax-ready markup instead."
  exit 1
fi

if ! grep -Fq 'class="arithmatex"' "$OUTPUT_HTML"; then
  echo "Missing arithmatex wrapper in generated HTML."
  exit 1
fi

if ! grep -Fq 'tex-mml-chtml.js' "$OUTPUT_HTML"; then
  echo "Missing MathJax runtime script in generated HTML."
  exit 1
fi

echo "MkDocs math rendering checks passed."
