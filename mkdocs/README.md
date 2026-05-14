# MkDocs 使用说明

本目录集中保存仓库的 MkDocs 基础设施：

- `mkdocs.yml`：MkDocs 配置文件
- `requirements.txt`：MkDocs Python 依赖
- `docs/`：MkDocs 站点入口目录
- `scripts/verify_math.sh`：数学公式渲染校验脚本
- `scripts/verify_layout_width.sh`：文档主体宽度配置校验脚本

说明：

- 各模块原始文档源文件仍然保留在各自目录中，没有移动。
- `mkdocs/docs/` 通过软链接接入这些模块文档。
- 生成后的静态站点输出到 `mkdocs/site/`。

## 在哪里执行命令

下面所有命令都从仓库根目录执行：

```bash
cd /home/feng/code/learning/java-learning
```

## 安装依赖

```bash
python3 -m pip install -r mkdocs/requirements.txt
```

## 构建静态站点

```bash
mkdocs build -f mkdocs/mkdocs.yml
```

构建结果输出到：

```text
mkdocs/site/
```

## GitHub Pages 自动发布

仓库包含 GitHub Actions 工作流：

```text
.github/workflows/pages.yml
```

触发条件：

- 推送到 `main` 分支，并且变更涉及 MkDocs 配置、站点入口、模块文档或引用资料
- 在 GitHub Actions 页面手动执行 `workflow_dispatch`

工作流会安装 `mkdocs/requirements.txt`，执行：

```bash
mkdocs build -f mkdocs/mkdocs.yml
```

然后把 `mkdocs/site/` 发布到 GitHub Pages。

发布地址：

```text
https://skt-shinyruo.github.io/java-learning/
```

首次启用时，在 GitHub 仓库设置里进入 `Settings -> Pages`，将 `Build and deployment` 的 `Source` 设置为 `GitHub Actions`。

## 本地预览

```bash
mkdocs serve -f mkdocs/mkdocs.yml
```

启动后默认访问：

```text
http://127.0.0.1:8000
```

## 配置文档布局宽度

文档布局默认宽度由 `mkdocs/mkdocs.yml` 中的配置控制：

```yaml
extra:
  layout:
    nav_width: compact
    content_width: compact
    toc_width: compact
    letter_spacing: compact
```

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

四个配置项分别控制：

- `nav_width`：左侧目录宽度
- `content_width`：中间正文宽度
- `toc_width`：右侧目录宽度
- `letter_spacing`：中间正文的字符间距

页面顶部的“布局”按钮也提供实时切换面板，可分别调整左侧目录、正文、右侧目录和正文字距。用户选择会保存到浏览器 `localStorage`，下次打开文档页时会自动恢复；清空浏览器数据或保存值无效时，会回退到 `mkdocs.yml` 中的默认值。

对应的 `localStorage` 键为：

- `java-learning-docs-nav-width`
- `java-learning-docs-content-width`
- `java-learning-docs-toc-width`
- `java-learning-docs-letter-spacing`

## 校验数学公式渲染

```bash
mkdocs/scripts/verify_math.sh
```

这个脚本会先执行一次构建，再检查生成后的 HTML 中是否包含正确的 MathJax 和 arithmatex 输出。

## 校验文档主体宽度配置

```bash
mkdocs/scripts/verify_layout_width.sh
```

这个脚本会先执行一次构建，再检查生成后的 HTML 是否带有左侧目录、正文、右侧目录的当前宽度层级和正文字距层级、早期恢复脚本和运行时切换脚本，以及 CSS 是否包含对应宽度和字距层级规则。
