# JL-05-LAB-04/05 死锁与线程阻塞 Runbook

## 1. 死锁实验

```bash
mvn -pl jvm -am -DskipTests package

java -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp deadlock --sleepSeconds 600
```

排查：

```bash
jps
jstack <pid> > target/jvm-lab-deadlock.txt
```

观察 `Found one Java-level deadlock`，并检查 `jvm-lab-deadlock-a` 和 `jvm-lab-deadlock-b` 分别持有什么锁、等待什么锁。

## 2. 线程阻塞实验

```bash
java -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp thread-block --waiters 3 --sleepSeconds 120
```

排查：

```bash
jstack <pid> > target/jvm-lab-blocked.txt
```

观察：

- `jvm-lab-thread-block-holder` 持有 monitor。
- `jvm-lab-thread-block-waiter-*` 处于 `BLOCKED`。

## 3. 修复方向

- 固定加锁顺序，避免环形等待。
- 缩小同步块。
- 使用超时锁或显式锁时确保释放。
- 降低共享锁竞争。
