# JL-05 线上故障排查实验

父级规格：`docs/superpowers/specs/jvm-lab/jl-05-troubleshooting-spec.md`

## 1. 实验目标

本实验组模拟线上 JVM 故障：高 CPU、静态集合内存泄漏、死锁、线程阻塞、频繁 GC 和直接内存 OOM。目标是从系统现象出发，拿到 JVM 证据，再定位到代码根因。

## 2. 编译

```bash
mvn -pl jvm -am -DskipTests package
```

## 3. 实验清单

| 编号 | 场景 | 入口 | 主要工具 |
| --- | --- | --- | --- |
| JL-05-LAB-01 | 高 CPU | `high-cpu` | `top -H`、`printf`、`jstack` |
| JL-05-LAB-02 | 静态集合内存泄漏 | `static-leak` | `jcmd`、`jmap`、MAT |
| JL-05-LAB-04 | 死锁 | `deadlock` | `jstack` |
| JL-05-LAB-05 | 线程阻塞 | `thread-block` | `jstack` |
| JL-05-LAB-06 | 频繁 GC | `gc-pressure` | `jstat`、GC log |
| JL-05-LAB-07 | Direct Memory OOM | `direct-oom` | BufferPoolMXBean、NMT |

`JL-05-LAB-03 ThreadLocal 泄漏` 留到后续批次，避免第 1 批同时引入过多线程生命周期场景。

## 4. 入口命令

```bash
java -cp jvm/target/classes yier.bubu.jvm.JvmLabApp help
```

## 5. 排查闭环

```text
现象 -> OS 指标 -> JVM 证据 -> 代码位置 -> 根因 -> 修复方案 -> 验证
```
