from __future__ import annotations

import json
import re

from mkdocs.config.defaults import MkDocsConfig


ALLOWED_LAYOUT_WIDTHS = ("compact", "comfortable", "wide", "full")
DEFAULT_LAYOUT_WIDTH = "compact"
LAYOUT_WIDTHS = {
    "nav_width": {
        "attribute": "data-docs-nav-width",
        "storage_key": "java-learning-docs-nav-width",
    },
    "content_width": {
        "attribute": "data-docs-content-width",
        "storage_key": "java-learning-docs-content-width",
    },
    "toc_width": {
        "attribute": "data-docs-toc-width",
        "storage_key": "java-learning-docs-toc-width",
    },
}


def _layout_width(config: MkDocsConfig, key: str) -> str:
    layout = config.extra.get("layout", {})
    width = layout.get(key, DEFAULT_LAYOUT_WIDTH)

    if width not in ALLOWED_LAYOUT_WIDTHS:
        values = ", ".join(ALLOWED_LAYOUT_WIDTHS)
        raise ValueError(
            f"extra.layout.{key} must be one of: {values}; got {width!r}"
        )

    return width


def _layout_width_values(config: MkDocsConfig) -> dict[str, str]:
    return {key: _layout_width(config, key) for key in LAYOUT_WIDTHS}


def on_config(config: MkDocsConfig) -> MkDocsConfig:
    layout = config.extra.setdefault("layout", {})
    for key in LAYOUT_WIDTHS:
        layout.setdefault(key, DEFAULT_LAYOUT_WIDTH)
    _layout_width_values(config)
    return config


def _restore_script(widths: dict[str, str]) -> str:
    settings = [
        {
            "attribute": meta["attribute"],
            "storageKey": meta["storage_key"],
            "defaultValue": widths[key],
        }
        for key, meta in LAYOUT_WIDTHS.items()
    ]
    settings_json = json.dumps(settings, separators=(",", ":"))
    return f"""<script id="docs-layout-width-restore">
  (function() {{
    var allowed = {{"compact": true, "comfortable": true, "wide": true, "full": true}};
    var settings = {settings_json};
    settings.forEach(function(setting) {{
      var width = setting.defaultValue;
      try {{
        var stored = localStorage.getItem(setting.storageKey);
        if (allowed[stored]) {{
          width = stored;
        }}
      }} catch (error) {{}}
      document.documentElement.setAttribute(setting.attribute, width);
    }});
  }})();
</script>"""


def on_post_page(output: str, *, page, config: MkDocsConfig) -> str:
    widths = _layout_width_values(config)
    attributes = " ".join(
        f'{meta["attribute"]}="{widths[key]}"' for key, meta in LAYOUT_WIDTHS.items()
    )
    output = re.sub(
        r"(<html\b(?![^>]*\bdata-docs-nav-width=)[^>]*)(>)",
        rf"\1 {attributes}\2",
        output,
        count=1,
    )
    if 'id="docs-layout-width-restore"' not in output:
        output = output.replace("<head>", f"<head>\n    {_restore_script(widths)}", 1)
    return output
