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
