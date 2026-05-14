# Docs Letter Spacing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add configurable article character spacing to the MkDocs site with a site default, runtime switcher, persisted browser preference, and verification coverage.

**Architecture:** Extend the existing layout-width pipeline instead of adding a parallel feature. The MkDocs hook validates defaults and injects `<html>` data attributes plus an early restore script; CSS consumes the attribute; the existing header switcher adds a "字距" row; the verification script builds the site and checks the generated contracts.

**Tech Stack:** MkDocs Material, Python MkDocs hooks, plain CSS, JavaScript compatible with the current browser script style, Bash verification script.

---

## File Structure

- Modify `mkdocs/scripts/verify_layout_width.sh`: add failing contract checks for `letter_spacing` configuration, generated HTML attributes, storage key, CSS rules, JS switcher markers, and labels.
- Modify `mkdocs/mkdocs.yml`: add `extra.layout.letter_spacing: compact` as the site default.
- Modify `mkdocs/hooks/layout_width.py`: validate `letter_spacing`, include it in `on_config`, add `data-docs-letter-spacing`, and restore saved values early.
- Modify `mkdocs/docs/stylesheets/extra.css`: define `--docs-letter-spacing` and map `compact`, `normal`, `wide`, and `extra` to concrete CSS values, applied only to main article reading content.
- Modify `mkdocs/docs/javascripts/layout-width.js`: generalize the existing width switcher to support separate option sets and add a "字距" setting persisted in `java-learning-docs-letter-spacing`.
- Modify `mkdocs/README.md`: document the new configuration value, UI behavior, and storage key.

## Task 1: Add Verification Coverage First

**Files:**
- Modify: `mkdocs/scripts/verify_layout_width.sh:9-175`

- [ ] **Step 1: Add `letter_spacing` config parsing near the existing width parsing**

Insert after `CONFIGURED_TOC_WIDTH`:

```bash
CONFIGURED_LETTER_SPACING="$(
  awk '/^[[:space:]]+letter_spacing:/ { print $2; exit }' "$ROOT_DIR/mkdocs/mkdocs.yml"
)"
```

- [ ] **Step 2: Add validation for the configured value**

Insert after the width validation loop:

```bash
case "$CONFIGURED_LETTER_SPACING" in
  compact|normal|wide|extra) ;;
  *)
    echo "Invalid docs letter spacing in mkdocs.yml: $CONFIGURED_LETTER_SPACING"
    exit 1
    ;;
esac
```

- [ ] **Step 3: Check the generated HTML attribute**

Insert after the `data-docs-toc-width` check:

```bash
if ! grep -Fq "data-docs-letter-spacing=\"$CONFIGURED_LETTER_SPACING\"" "$OUTPUT_HTML"; then
  echo "Missing docs letter spacing attribute on generated HTML element: $CONFIGURED_LETTER_SPACING."
  exit 1
fi
```

- [ ] **Step 4: Check the CSS letter-spacing rules**

Insert after the existing width CSS loop:

```bash
for spacing in compact normal wide extra; do
  if ! grep -Fq "html[data-docs-letter-spacing=\"$spacing\"]" "$EXTRA_CSS"; then
    echo "Missing CSS rule for docs letter spacing: $spacing."
    exit 1
  fi
done
```

- [ ] **Step 5: Check the CSS custom property and scoped article selector**

Insert after the existing CSS variable checks:

```bash
if ! grep -Fq -- "--docs-letter-spacing" "$EXTRA_CSS"; then
  echo "Missing letter spacing CSS variable."
  exit 1
fi

if ! grep -Fq ".md-content__inner" "$EXTRA_CSS"; then
  echo "Missing article content selector for docs letter spacing."
  exit 1
fi
```

- [ ] **Step 6: Add the letter spacing storage key to generated HTML and JS checks**

Extend the `for storage_key in \` block so it contains:

```bash
for storage_key in \
  java-learning-docs-nav-width \
  java-learning-docs-content-width \
  java-learning-docs-toc-width \
  java-learning-docs-letter-spacing
do
```

- [ ] **Step 7: Add runtime switcher marker checks**

Insert after the `data-docs-layout-width-value` marker check:

```bash
if ! grep -Fq 'data-docs-layout-setting-target' "$LAYOUT_JS"; then
  echo "Missing runtime docs layout setting target markers."
  exit 1
fi

if ! grep -Fq 'data-docs-layout-setting-value' "$LAYOUT_JS"; then
  echo "Missing runtime docs layout setting value markers."
  exit 1
fi
```

- [ ] **Step 8: Add readable labels for the new UI**

Replace:

```bash
for label in 布局 左侧目录 正文 右侧目录 紧凑 中等 最大; do
```

with:

```bash
for label in 布局 左侧目录 正文 右侧目录 字距 紧凑 常规 宽松 最大; do
```

- [ ] **Step 9: Run verification to confirm the test fails**

Run: `mkdocs/scripts/verify_layout_width.sh`

Expected: FAIL with a message about invalid or missing `letter_spacing` before implementation is added.

## Task 2: Extend MkDocs Defaults and Hook Injection

**Files:**
- Modify: `mkdocs/mkdocs.yml:51-55`
- Modify: `mkdocs/hooks/layout_width.py:9-93`

- [ ] **Step 1: Add the default setting to MkDocs config**

Change the `extra.layout` block to:

```yaml
extra:
  layout:
    nav_width: compact
    content_width: compact
    toc_width: compact
    letter_spacing: compact
```

- [ ] **Step 2: Split width and spacing constants in the hook**

Replace the top constants and layout metadata with:

```python
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
```

- [ ] **Step 3: Replace `_layout_width` and `_layout_width_values` with generic helpers**

Use:

```python
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
```

- [ ] **Step 4: Update `on_config` to set defaults for all settings**

Use:

```python
def on_config(config: MkDocsConfig) -> MkDocsConfig:
    layout = config.extra.setdefault("layout", {})
    for key, meta in LAYOUT_SETTINGS.items():
        layout.setdefault(key, meta["default"])
    _layout_setting_values(config)
    return config
```

- [ ] **Step 5: Update `_restore_script` to carry per-setting allowed values**

Use:

```python
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
        if (setting.allowed[stored]) {{
          value = stored;
        }}
      }} catch (error) {{}}
      document.documentElement.setAttribute(setting.attribute, value);
    }});
  }})();
</script>"""
```

- [ ] **Step 6: Update `on_post_page` to inject all settings**

Use:

```python
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
```

- [ ] **Step 7: Run verification**

Run: `mkdocs/scripts/verify_layout_width.sh`

Expected: FAIL because CSS and JavaScript letter-spacing contracts are not implemented yet.

## Task 3: Add CSS for Article Letter Spacing

**Files:**
- Modify: `mkdocs/docs/stylesheets/extra.css:19-75`

- [ ] **Step 1: Add a default CSS variable**

Add to the existing `html` block:

```css
  --docs-letter-spacing: normal;
```

- [ ] **Step 2: Add spacing value mappings after the toc width rules**

Insert:

```css
html[data-docs-letter-spacing="compact"] {
  --docs-letter-spacing: normal;
}

html[data-docs-letter-spacing="normal"] {
  --docs-letter-spacing: 0.02em;
}

html[data-docs-letter-spacing="wide"] {
  --docs-letter-spacing: 0.04em;
}

html[data-docs-letter-spacing="extra"] {
  --docs-letter-spacing: 0.06em;
}
```

- [ ] **Step 3: Apply spacing only to article reading elements**

Insert after the spacing mappings:

```css
.md-content__inner > p,
.md-content__inner > ul,
.md-content__inner > ol,
.md-content__inner > blockquote,
.md-content__inner > table {
  letter-spacing: var(--docs-letter-spacing);
}
```

- [ ] **Step 4: Run verification**

Run: `mkdocs/scripts/verify_layout_width.sh`

Expected: FAIL because JavaScript switcher contracts are not implemented yet.

## Task 4: Extend the Runtime Layout Switcher

**Files:**
- Modify: `mkdocs/docs/javascripts/layout-width.js:1-218`

- [ ] **Step 1: Replace global value constants with grouped options**

Replace lines 2-14 with:

```javascript
  var optionSets = {
    width: {
      values: ["compact", "comfortable", "wide", "full"],
      labels: {
        compact: "紧凑",
        comfortable: "中等",
        wide: "宽屏",
        full: "最大"
      },
      titles: {
        compact: "compact",
        comfortable: "comfortable",
        wide: "wide",
        full: "full"
      },
      fallback: "compact"
    },
    spacing: {
      values: ["compact", "normal", "wide", "extra"],
      labels: {
        compact: "紧凑",
        normal: "常规",
        wide: "宽松",
        extra: "最大"
      },
      titles: {
        compact: "compact",
        normal: "normal",
        wide: "wide",
        extra: "extra"
      },
      fallback: "compact"
    }
  };
```

- [ ] **Step 2: Add `optionSet` to existing settings and add the new spacing setting**

Replace the `settings` array with:

```javascript
  var settings = [
    {
      key: "nav",
      label: "左侧目录",
      title: "左目录宽度",
      attribute: "data-docs-nav-width",
      storageKey: "java-learning-docs-nav-width",
      optionSet: "width"
    },
    {
      key: "content",
      label: "正文",
      title: "正文宽度",
      attribute: "data-docs-content-width",
      storageKey: "java-learning-docs-content-width",
      optionSet: "width"
    },
    {
      key: "toc",
      label: "右侧目录",
      title: "右目录宽度",
      attribute: "data-docs-toc-width",
      storageKey: "java-learning-docs-toc-width",
      optionSet: "width"
    },
    {
      key: "letter-spacing",
      label: "字距",
      title: "正文字距",
      attribute: "data-docs-letter-spacing",
      storageKey: "java-learning-docs-letter-spacing",
      optionSet: "spacing"
    }
  ];
```

- [ ] **Step 3: Replace `isAllowed` with per-setting option helpers**

Use:

```javascript
  function getOptionSet(setting) {
    return optionSets[setting.optionSet] || optionSets.width;
  }

  function isAllowed(setting, value) {
    return getOptionSet(setting).values.indexOf(value) !== -1;
  }
```

- [ ] **Step 4: Update stored/current/apply helpers to use generic value names**

Replace `getStoredWidth`, `getCurrentWidth`, `saveWidth`, and `applyWidth` with:

```javascript
  function getStoredValue(setting) {
    try {
      var stored = localStorage.getItem(setting.storageKey);
      if (isAllowed(setting, stored)) {
        return stored;
      }
      if (stored !== null) {
        localStorage.removeItem(setting.storageKey);
      }
    } catch (error) {}
    return null;
  }

  function getCurrentValue(setting) {
    var current = document.documentElement.getAttribute(setting.attribute);
    var options = getOptionSet(setting);
    return isAllowed(setting, current) ? current : options.fallback;
  }

  function saveValue(setting, value) {
    try {
      localStorage.setItem(setting.storageKey, value);
    } catch (error) {}
  }

  function applyValue(setting, value, persist) {
    var options = getOptionSet(setting);
    if (!isAllowed(setting, value)) {
      value = options.fallback;
    }
    document.documentElement.setAttribute(setting.attribute, value);
    syncButtons(setting, value);
    if (persist) {
      saveValue(setting, value);
    }
  }
```

- [ ] **Step 5: Update button selector markers**

Replace `syncButtons` with:

```javascript
  function syncButtons(setting, value) {
    var selector =
      "[data-docs-layout-setting-target='" + setting.key + "'] " +
      "[data-docs-layout-setting-value]";
    var buttons = document.querySelectorAll(selector);
    for (var i = 0; i < buttons.length; i += 1) {
      var button = buttons[i];
      var active = button.getAttribute("data-docs-layout-setting-value") === value;
      button.setAttribute("aria-pressed", active ? "true" : "false");
    }
  }
```

- [ ] **Step 6: Update `createSetting` to render the per-setting option set**

Use:

```javascript
  function createSetting(setting) {
    var row = document.createElement("div");
    var optionsConfig = getOptionSet(setting);
    row.className = "docs-layout-widths__setting";
    row.setAttribute("data-docs-layout-width-target", setting.key);
    row.setAttribute("data-docs-layout-setting-target", setting.key);

    var label = document.createElement("span");
    label.className = "docs-layout-widths__label";
    label.textContent = setting.label;
    label.title = setting.title;
    row.appendChild(label);

    var options = document.createElement("div");
    options.className = "docs-layout-widths__options";
    options.setAttribute("role", "group");
    options.setAttribute("aria-label", setting.title);

    optionsConfig.values.forEach(function (value) {
      var button = document.createElement("button");
      button.className = "docs-layout-widths__button";
      button.type = "button";
      button.textContent = optionsConfig.labels[value];
      button.title = setting.title + "：" + optionsConfig.titles[value];
      button.setAttribute("aria-label", setting.title + "：" + optionsConfig.titles[value]);
      button.setAttribute("aria-pressed", "false");
      button.setAttribute("data-docs-layout-width-value", value);
      button.setAttribute("data-docs-layout-setting-value", value);
      button.addEventListener("click", function () {
        applyValue(setting, value, true);
      });
      options.appendChild(button);
    });

    row.appendChild(options);
    return row;
  }
```

- [ ] **Step 7: Update switcher accessible text**

Change:

```javascript
    trigger.title = "调整文档布局宽度";
    trigger.setAttribute("aria-label", "调整文档布局宽度");
```

to:

```javascript
    trigger.title = "调整文档布局";
    trigger.setAttribute("aria-label", "调整文档布局");
```

Change:

```javascript
    panel.setAttribute("aria-label", "文档布局宽度");
```

to:

```javascript
    panel.setAttribute("aria-label", "文档布局");
```

- [ ] **Step 8: Update mount logic**

Replace:

```javascript
    settings.forEach(function (setting) {
      applyWidth(setting, getStoredWidth(setting) || getCurrentWidth(setting), false);
    });
```

with:

```javascript
    settings.forEach(function (setting) {
      applyValue(setting, getStoredValue(setting) || getCurrentValue(setting), false);
    });
```

- [ ] **Step 9: Run verification**

Run: `mkdocs/scripts/verify_layout_width.sh`

Expected: PASS for generated contracts, unless README documentation is still unmodified. If it fails, the message should identify a concrete missing contract.

## Task 5: Update README Documentation

**Files:**
- Modify: `mkdocs/README.md:86-131`

- [ ] **Step 1: Add `letter_spacing` to the YAML example**

Change:

```yaml
extra:
  layout:
    nav_width: compact
    content_width: compact
    toc_width: compact
```

to:

```yaml
extra:
  layout:
    nav_width: compact
    content_width: compact
    toc_width: compact
    letter_spacing: compact
```

- [ ] **Step 2: Document width values and spacing values separately**

Replace the value description with:

```markdown
宽度配置可选值从窄到宽依次为：

- `compact`：当前默认宽度，也是最小层级
- `comfortable`：中等加宽
- `wide`：宽屏阅读
- `full`：最大层级

字距配置 `letter_spacing` 可选值为：

- `compact`：浏览器默认字距
- `normal`：轻微增加字距
- `wide`：宽松字距
- `extra`：最大字距
```

- [ ] **Step 3: Add the fourth config item**

Replace:

```markdown
三个配置项分别控制：
```

with:

```markdown
四个配置项分别控制：
```

Add:

```markdown
- `letter_spacing`：中间正文的字符间距
```

- [ ] **Step 4: Update runtime UI description**

Change the paragraph about the "布局" button to:

```markdown
页面顶部的“布局”按钮也提供实时切换面板，可分别调整左侧目录、正文、右侧目录和正文字距。用户选择会保存到浏览器 `localStorage`，下次打开文档页时会自动恢复；清空浏览器数据或保存值无效时，会回退到 `mkdocs.yml` 中的默认值。
```

- [ ] **Step 5: Add the storage key**

Add:

```markdown
- `java-learning-docs-letter-spacing`
```

- [ ] **Step 6: Update verification description**

Change the final sentence to:

```markdown
这个脚本会先执行一次构建，再检查生成后的 HTML 是否带有左侧目录、正文、右侧目录的当前宽度层级和正文字距层级、早期恢复脚本和运行时切换脚本，以及 CSS 是否包含对应宽度和字距层级规则。
```

## Task 6: Final Verification and Commit

**Files:**
- Verify: `mkdocs/scripts/verify_layout_width.sh`
- Verify: `git diff`

- [ ] **Step 1: Run the full docs layout verification**

Run: `mkdocs/scripts/verify_layout_width.sh`

Expected: PASS with:

```text
MkDocs layout width checks passed.
```

- [ ] **Step 2: Inspect the generated HTML contract**

Run: `grep -F 'data-docs-letter-spacing="compact"' mkdocs/site/nio/content/nio-direct-memory/index.html`

Expected: At least one line containing `data-docs-letter-spacing="compact"`.

- [ ] **Step 3: Inspect the working tree**

Run: `git diff --stat`

Expected: source changes in `mkdocs/` files only, plus generated `mkdocs/site/` changes if the site directory is tracked or present. Do not stage generated `mkdocs/site/` files.

- [ ] **Step 4: Stage only source files**

Run:

```bash
git add \
  mkdocs/mkdocs.yml \
  mkdocs/hooks/layout_width.py \
  mkdocs/docs/stylesheets/extra.css \
  mkdocs/docs/javascripts/layout-width.js \
  mkdocs/scripts/verify_layout_width.sh \
  mkdocs/README.md
```

- [ ] **Step 5: Commit implementation**

Run:

```bash
git commit -m "feat: add configurable docs letter spacing"
```

- [ ] **Step 6: Confirm only pre-existing user changes remain**

Run: `git status --short`

Expected: no staged changes. The pre-existing modified file under `references/深入理解Java虚拟机_JVM高级特性与最佳实践_第3版/chapters/10-第6章-类文件结构.md` may still be present and must remain untouched.

## Self-Review

- Spec coverage: Tasks 2 and 3 implement config and article styling; Task 4 implements runtime control and persistence; Task 5 documents behavior; Tasks 1 and 6 verify the generated contracts.
- Placeholder scan: no unresolved markers or vague implementation steps remain.
- Type consistency: Python uses `LAYOUT_SETTINGS`; JavaScript uses `optionSets`, `getStoredValue`, `getCurrentValue`, `applyValue`, `data-docs-layout-setting-*`; Bash verifies both legacy width markers and new generic setting markers.
