# JL-02-LAB-03 Metaspace OOM 排查 Runbook

## 1. 启动实验

```bash
mvn -pl jvm -am -DskipTests package

java -XX:MaxMetaspaceSize=64m \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp metaspace-oom --count 20000 --reportEvery 1000
```

## 2. 预期现象

程序动态定义大量类，并持有 `Class` 引用和定义这些类的 ClassLoader。元空间持续增长，可能抛出 `java.lang.OutOfMemoryError: Metaspace`。

## 3. 观察命令

```bash
jcmd <pid> GC.class_stats
jcmd <pid> GC.class_histogram
jcmd <pid> VM.classloader_stats
jcmd <pid> GC.heap_info
```

不同 JDK 支持的 `jcmd` 子命令会有差异，以 `jcmd <pid> help` 为准。

## 4. 根因判断

类元数据主要在 Metaspace 中。类卸载依赖定义类的 ClassLoader 不再可达；如果 ClassLoader 泄漏，类元数据也无法释放。

## 5. 修复方向

- 避免重复生成无限数量的类。
- 清理持有 ClassLoader 的静态集合、线程上下文类加载器和缓存。
- 检查热部署、插件系统和动态代理生成策略。
