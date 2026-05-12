# MkDocs 使用说明

本目录集中保存仓库的 MkDocs 基础设施：

- `mkdocs.yml`：MkDocs 配置文件
- `requirements.txt`：MkDocs Python 依赖
- `docs/`：MkDocs 站点入口目录
- `scripts/verify_math.sh`：数学公式渲染校验脚本

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

## 校验数学公式渲染

```bash
mkdocs/scripts/verify_math.sh
```

这个脚本会先执行一次构建，再检查生成后的 HTML 中是否包含正确的 MathJax 和 arithmatex 输出。
