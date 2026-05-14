from __future__ import annotations

import json
import re

from mkdocs.config.defaults import MkDocsConfig


ALLOWED_LAYOUT_WIDTHS = ("compact", "comfortable", "wide", "full")
ALLOWED_LETTER_SPACINGS = ("compact", "normal", "wide", "extra")
DEFAULT_LAYOUT_WIDTH = "compact"
DEFAULT_LETTER_SPACING = "compact"
LAYOUT_SETTINGS = {
    "nav_width": {
        "attribute": "data-docs-nav-width",
        "storage_key": "java-learning-docs-nav-width",
        "allowed": ALLOWED_LAYOUT_WIDTHS,
        "default": DEFAULT_LAYOUT_WIDTH,
    },
    "content_width": {
        "attribute": "data-docs-content-width",
        "storage_key": "java-learning-docs-content-width",
        "allowed": ALLOWED_LAYOUT_WIDTHS,
        "default": DEFAULT_LAYOUT_WIDTH,
    },
    "toc_width": {
        "attribute": "data-docs-toc-width",
        "storage_key": "java-learning-docs-toc-width",
        "allowed": ALLOWED_LAYOUT_WIDTHS,
        "default": DEFAULT_LAYOUT_WIDTH,
    },
    "letter_spacing": {
        "attribute": "data-docs-letter-spacing",
        "storage_key": "java-learning-docs-letter-spacing",
        "allowed": ALLOWED_LETTER_SPACINGS,
        "default": DEFAULT_LETTER_SPACING,
    },
}


def _layout_setting(config: MkDocsConfig, key: str) -> str:
    layout = config.extra.get("layout", {})
    meta = LAYOUT_SETTINGS[key]
    value = layout.get(key, meta["default"])

    if value not in meta["allowed"]:
        values = ", ".join(meta["allowed"])
        raise ValueError(
            f"extra.layout.{key} must be one of: {values}; got {value!r}"
        )

    return value


def _layout_setting_values(config: MkDocsConfig) -> dict[str, str]:
    return {key: _layout_setting(config, key) for key in LAYOUT_SETTINGS}


def on_config(config: MkDocsConfig) -> MkDocsConfig:
    layout = config.extra.setdefault("layout", {})
    for key, meta in LAYOUT_SETTINGS.items():
        layout.setdefault(key, meta["default"])
    _layout_setting_values(config)
    return config


def _restore_script(values: dict[str, str]) -> str:
    settings = [
        {
            "attribute": meta["attribute"],
            "storageKey": meta["storage_key"],
            "defaultValue": values[key],
            "allowed": {value: True for value in meta["allowed"]},
        }
        for key, meta in LAYOUT_SETTINGS.items()
    ]
    settings_json = json.dumps(settings, separators=(",", ":"))
    return f"""<script id="docs-layout-width-restore">
  (function() {{
    var settings = {settings_json};
    settings.forEach(function(setting) {{
      var value = setting.defaultValue;
      try {{
        var stored = localStorage.getItem(setting.storageKey);
        if (Object.prototype.hasOwnProperty.call(setting.allowed, stored)) {{
          value = stored;
        }}
      }} catch (error) {{}}
      document.documentElement.setAttribute(setting.attribute, value);
    }});
  }})();
</script>"""


def on_post_page(output: str, *, page, config: MkDocsConfig) -> str:
    values = _layout_setting_values(config)
    attributes = " ".join(
        f'{meta["attribute"]}="{values[key]}"'
        for key, meta in LAYOUT_SETTINGS.items()
    )
    output = re.sub(
        r"(<html\b(?![^>]*\bdata-docs-nav-width=)[^>]*)(>)",
        rf"\1 {attributes}\2",
        output,
        count=1,
    )
    if 'id="docs-layout-width-restore"' not in output:
        output = output.replace("<head>", f"<head>\n    {_restore_script(values)}", 1)
    return output
