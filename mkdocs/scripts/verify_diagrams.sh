#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JVM_MEMORY_HTML="$ROOT_DIR/mkdocs/site/jvm/content/jvm-memory/index.html"
JAVA_OBJECT_LAYOUT_HTML="$ROOT_DIR/mkdocs/site/jvm/content/java-object-layout/index.html"

cd "$ROOT_DIR"

mkdocs build -f mkdocs/mkdocs.yml >/dev/null

test -f "$JVM_MEMORY_HTML"
test -f "$JAVA_OBJECT_LAYOUT_HTML"

if grep -Fq '<p><rect' "$JVM_MEMORY_HTML" "$JAVA_OBJECT_LAYOUT_HTML"; then
  echo "Found SVG child nodes split into paragraph HTML; use external SVG files or keep inline SVG compact."
  exit 1
fi

if ! grep -Fq 'jvm-runtime-data-areas.svg' "$JVM_MEMORY_HTML"; then
  echo "Missing rendered JVM runtime data areas diagram image."
  exit 1
fi

if ! grep -Fq 'hotspot-process-memory.svg' "$JVM_MEMORY_HTML"; then
  echo "Missing rendered HotSpot process memory diagram image."
  exit 1
fi

if ! grep -Fq 'java-object-layout-overview.svg' "$JAVA_OBJECT_LAYOUT_HTML"; then
  echo "Missing rendered Java object layout overview diagram image."
  exit 1
fi

if ! grep -Fq 'method-area-hotspot.svg' "$JVM_MEMORY_HTML"; then
  echo "Missing rendered method area HotSpot diagram image."
  exit 1
fi

echo "MkDocs diagram rendering checks passed."
