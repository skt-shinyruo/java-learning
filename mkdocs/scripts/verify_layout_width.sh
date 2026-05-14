#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUTPUT_HTML="$ROOT_DIR/mkdocs/site/nio/content/nio-direct-memory/index.html"
EXTRA_CSS="$ROOT_DIR/mkdocs/site/stylesheets/extra.css"
LAYOUT_JS="$ROOT_DIR/mkdocs/site/javascripts/layout-width.js"
CONFIGURED_NAV_WIDTH="$(
  awk '/^[[:space:]]+nav_width:/ { print $2; exit }' "$ROOT_DIR/mkdocs/mkdocs.yml"
)"
CONFIGURED_CONTENT_WIDTH="$(
  awk '/^[[:space:]]+content_width:/ { print $2; exit }' "$ROOT_DIR/mkdocs/mkdocs.yml"
)"
CONFIGURED_TOC_WIDTH="$(
  awk '/^[[:space:]]+toc_width:/ { print $2; exit }' "$ROOT_DIR/mkdocs/mkdocs.yml"
)"
CONFIGURED_LETTER_SPACING="$(
  awk '/^[[:space:]]+letter_spacing:/ { print $2; exit }' "$ROOT_DIR/mkdocs/mkdocs.yml"
)"

cd "$ROOT_DIR"

for configured_width in "$CONFIGURED_NAV_WIDTH" "$CONFIGURED_CONTENT_WIDTH" "$CONFIGURED_TOC_WIDTH"; do
  case "$configured_width" in
    compact|comfortable|wide|full) ;;
    *)
      echo "Invalid docs layout width in mkdocs.yml: $configured_width"
      exit 1
      ;;
  esac
done

case "$CONFIGURED_LETTER_SPACING" in
  compact|normal|wide|extra) ;;
  *)
    echo "Invalid docs letter spacing in mkdocs.yml: $CONFIGURED_LETTER_SPACING"
    exit 1
    ;;
esac

mkdocs build -f mkdocs/mkdocs.yml >/dev/null

for file in "$OUTPUT_HTML" "$EXTRA_CSS" "$LAYOUT_JS"; do
  if [ ! -f "$file" ]; then
    echo "Missing generated file: $file"
    exit 1
  fi
done

if ! grep -Fq "data-docs-nav-width=\"$CONFIGURED_NAV_WIDTH\"" "$OUTPUT_HTML"; then
  echo "Missing docs nav width attribute on generated HTML element: $CONFIGURED_NAV_WIDTH."
  exit 1
fi

if ! grep -Fq "data-docs-content-width=\"$CONFIGURED_CONTENT_WIDTH\"" "$OUTPUT_HTML"; then
  echo "Missing docs content width attribute on generated HTML element: $CONFIGURED_CONTENT_WIDTH."
  exit 1
fi

if ! grep -Fq "data-docs-toc-width=\"$CONFIGURED_TOC_WIDTH\"" "$OUTPUT_HTML"; then
  echo "Missing docs toc width attribute on generated HTML element: $CONFIGURED_TOC_WIDTH."
  exit 1
fi

if ! grep -Fq "data-docs-letter-spacing=\"$CONFIGURED_LETTER_SPACING\"" "$OUTPUT_HTML"; then
  echo "Missing docs letter spacing attribute on generated HTML element: $CONFIGURED_LETTER_SPACING."
  exit 1
fi

if ! grep -Fq 'id="docs-layout-width-restore"' "$OUTPUT_HTML"; then
  echo "Missing early docs layout width restore script in generated HTML."
  exit 1
fi

if ! grep -Fq 'javascripts/layout-width.js' "$OUTPUT_HTML"; then
  echo "Missing runtime docs layout width switcher script in generated HTML."
  exit 1
fi

for width in compact comfortable wide full; do
  if ! grep -Fq "html[data-docs-nav-width=\"$width\"]" "$EXTRA_CSS"; then
    echo "Missing CSS rule for docs nav width: $width."
    exit 1
  fi

  if ! grep -Fq "html[data-docs-content-width=\"$width\"]" "$EXTRA_CSS"; then
    echo "Missing CSS rule for docs content width: $width."
    exit 1
  fi

  if ! grep -Fq "html[data-docs-toc-width=\"$width\"]" "$EXTRA_CSS"; then
    echo "Missing CSS rule for docs toc width: $width."
    exit 1
  fi
done

for spacing in compact normal wide extra; do
  if ! grep -Fq "html[data-docs-letter-spacing=\"$spacing\"]" "$EXTRA_CSS"; then
    echo "Missing CSS rule for docs letter spacing: $spacing."
    exit 1
  fi
done

if ! grep -Fq "var(--docs-content-grid-width)" "$EXTRA_CSS"; then
  echo "Missing content width variable in grid calculation."
  exit 1
fi

if ! grep -Fq "var(--docs-nav-width)" "$EXTRA_CSS"; then
  echo "Missing nav width variable in grid calculation."
  exit 1
fi

if ! grep -Fq "var(--docs-toc-width)" "$EXTRA_CSS"; then
  echo "Missing toc width variable in grid calculation."
  exit 1
fi

if ! grep -Fq -- "--docs-letter-spacing" "$EXTRA_CSS"; then
  echo "Missing letter spacing CSS variable."
  exit 1
fi

if ! grep -Fq ".md-content__inner" "$EXTRA_CSS"; then
  echo "Missing article content selector for docs letter spacing."
  exit 1
fi

if ! grep -Fq ".md-sidebar--primary" "$EXTRA_CSS"; then
  echo "Missing primary sidebar width override."
  exit 1
fi

if ! grep -Fq ".md-sidebar--secondary" "$EXTRA_CSS"; then
  echo "Missing secondary sidebar width override."
  exit 1
fi

if ! grep -Fq -- "--docs-sidebar-inner-offset" "$EXTRA_CSS"; then
  echo "Missing fixed sidebar inner padding offset."
  exit 1
fi

if grep -Fq "calc(var(--docs-nav-width) - 11.5rem)" "$EXTRA_CSS"; then
  echo "Primary sidebar inner padding must not grow with nav width."
  exit 1
fi

if grep -Fq "calc(var(--docs-toc-width) - 11.5rem)" "$EXTRA_CSS"; then
  echo "Secondary sidebar inner padding must not grow with toc width."
  exit 1
fi

for storage_key in \
  java-learning-docs-nav-width \
  java-learning-docs-content-width \
  java-learning-docs-toc-width \
  java-learning-docs-letter-spacing
do
  if ! grep -Fq "$storage_key" "$OUTPUT_HTML"; then
    echo "Missing localStorage key in early restore script: $storage_key."
    exit 1
  fi

  if ! grep -Fq "$storage_key" "$LAYOUT_JS"; then
    echo "Missing localStorage key in runtime switcher script: $storage_key."
    exit 1
  fi
done

if ! grep -Fq 'setAttribute("data-md-component", "docs-layout-widths")' "$LAYOUT_JS"; then
  echo "Missing runtime docs layout widths switcher component."
  exit 1
fi

if ! grep -Fq 'data-docs-layout-width-target' "$LAYOUT_JS"; then
  echo "Missing runtime docs layout width target markers."
  exit 1
fi

if ! grep -Fq 'data-docs-layout-width-value' "$LAYOUT_JS"; then
  echo "Missing runtime docs layout width value markers."
  exit 1
fi

if ! grep -Fq 'data-docs-layout-setting-target' "$LAYOUT_JS"; then
  echo "Missing runtime docs layout setting target markers."
  exit 1
fi

if ! grep -Fq 'data-docs-layout-setting-value' "$LAYOUT_JS"; then
  echo "Missing runtime docs layout setting value markers."
  exit 1
fi

if ! grep -Fq 'docs-layout-widths__trigger' "$LAYOUT_JS"; then
  echo "Missing compact layout switcher trigger button."
  exit 1
fi

if ! grep -Fq 'docs-layout-widths__panel' "$LAYOUT_JS"; then
  echo "Missing grouped layout switcher panel."
  exit 1
fi

for label in 布局 左侧目录 正文 右侧目录 字距 紧凑 中等 宽屏 常规 宽松 最大; do
  if ! grep -Fq "$label" "$LAYOUT_JS"; then
    echo "Missing readable layout switcher label: $label."
    exit 1
  fi
done

if ! grep -Fq '.docs-layout-widths__panel' "$EXTRA_CSS"; then
  echo "Missing layout switcher panel styles."
  exit 1
fi

echo "MkDocs layout width checks passed."
