# JL-01 字节码与类加载 Spec

父级规格：[JVM 实战靶场总 Spec](./jvm-lab-master-spec.md)

## 1. 目标

建设一组字节码和类加载实验，让学习者能从 Class 文件、字节码指令、常量池和 ClassLoader 类型身份角度理解 Java 代码的运行方式，而不是只停留在语法和概念层面。

## 2. 范围

本 spec 覆盖：

- `javap -v -p` 分析常见 Java 语法。
- 简化 Class 文件解析器。
- 自定义 ClassLoader 从磁盘加载 `.class` 文件。
- 打破双亲委派的受控实验。
- 不同 ClassLoader 加载同名类导致的类型身份差异。

已有相关文档包括 `jvm/docs/class-file-structure.md`、`jvm/docs/class-file-advanced-structures.md`、`jvm/docs/class-loader-parent-delegation.md`，后续应优先链接或扩展这些文档。

## 3. 实验清单

| 实验编号 | 名称 | 交付重点 |
| --- | --- | --- |
| JL-01-LAB-01 | 常见语法字节码观察 | `synchronized`、`try-finally`、lambda、`count++`、泛型擦除、`switch` |
| JL-01-LAB-02 | 简化 Class 文件解析器 | magic、版本号、常量池、访问标志、类名、父类名、字段、方法 |
| JL-01-LAB-03 | 自定义 ClassLoader | 读取 class 字节、`defineClass`、实例化、反射调用方法 |
| JL-01-LAB-04 | 类型身份与 ClassCastException | 同名类由不同 ClassLoader 加载后不是同一类型 |

## 4. 代码设计约束

- 包名建议使用 `yier.bubu.jvm.bytecode` 和 `yier.bubu.jvm.classloading`。
- Class 文件解析器只实现学习目标所需字段，不追求完整 JVM 规范覆盖。
- 自定义 ClassLoader 优先重写 `findClass()`，只在打破双亲委派实验中显式说明为什么要改 `loadClass()`。
- 生成的临时 `.class` 文件放在 `target/` 下，不提交到仓库。
- 所有示例默认保持 Java 8 兼容。涉及 `invokedynamic` 的 lambda 示例可使用 Java 8 编译结果。

## 5. 文档设计

建议新增或扩展：

- `jvm/docs/labs/jl-01-bytecode-lab.md`
- `jvm/docs/labs/jl-01-classloader-lab.md`
- `jvm/docs/reports/jl-01-classloader-type-identity-report.md`

文档必须包含：

- 编译命令。
- `javap` 命令。
- 关键输出观察点。
- 字节码和 Java 源码之间的对应关系。
- ClassLoader 类型身份结论：`类唯一性 = 全限定类名 + 定义类加载器`。

## 6. 验收标准

- `mvn -pl jvm -am test` 通过。
- 每个实验能从仓库根目录运行。
- `javap` 文档不依赖固定常量池编号作为唯一证据，因为不同 JDK 编译器可能改变编号。
- ClassLoader 实验能稳定展示不同加载器导致的 `instanceof` 或强转差异。
- 文档链接到父 spec 和已有 JVM 相关文档。

## 7. 非目标

- 不实现完整 Class 文件解析器。
- 不实现 Java 解释器或字节码执行引擎。
- 不在普通测试中依赖机器本地绝对路径。
