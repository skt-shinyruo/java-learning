# JL-02-LAB-01 Heap OOM 复盘模板

## 1. 现象

程序运行后抛出 `java.lang.OutOfMemoryError: Java heap space`。

## 2. JVM 参数

```text
-Xms64m -Xmx64m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=target/jvm-lab-heap.hprof
```

## 3. 排查工具

- `jcmd`
- `jmap`
- MAT 或 VisualVM

## 4. 关键证据

- 最大对象类型：
- Retained Heap：
- GC Roots：
- 引用链：

## 5. 根因

集合持续持有对象引用，导致对象仍然可达，GC 无法回收。

## 6. 解决方案

- 限制集合大小。
- 使用淘汰策略。
- 分批处理后清理引用。

## 7. 验证方式

重新运行程序，观察 heap 使用是否稳定，或确认新的 heap dump 中 retained heap 不再无界增长。

## 8. 总结

不是 GC 不工作，而是对象仍然可达。
