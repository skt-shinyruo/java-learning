#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUTPUT_HTML="$ROOT_DIR/mkdocs/site/jvm/content/jvm-memory/index.html"

cd "$ROOT_DIR"

mkdocs build -f mkdocs/mkdocs.yml >/dev/null

test -f "$OUTPUT_HTML"

if grep -Fq '<p><rect' "$OUTPUT_HTML"; then
  echo "Found SVG child nodes split into paragraph HTML; use external SVG files or keep inline SVG compact."
  exit 1
fi

if ! grep -Fq 'jvm-runtime-data-areas.svg' "$OUTPUT_HTML"; then
  echo "Missing rendered JVM runtime data areas diagram image."
  exit 1
fi

if ! grep -Fq 'hotspot-process-memory.svg' "$OUTPUT_HTML"; then
  echo "Missing rendered HotSpot process memory diagram image."
  exit 1
fi

if ! grep -Fq 'method-area-hotspot.svg' "$OUTPUT_HTML"; then
  echo "Missing rendered method area HotSpot diagram image."
  exit 1
fi

echo "MkDocs diagram rendering checks passed."
