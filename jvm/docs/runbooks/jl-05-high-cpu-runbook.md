# JL-05-LAB-01 高 CPU 排查 Runbook

## 1. 启动实验

```bash
mvn -pl jvm -am -DskipTests package

java -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp high-cpu --threads 1 --seconds 120
```

## 2. 排查命令

```bash
top
top -H -p <pid>
printf "%x\n" <tid>
jstack <pid> > target/jvm-lab-thread.txt
```

## 3. 判断方法

1. 在 `top -H -p <pid>` 中找到 CPU 高的线程 ID。
2. 用 `printf "%x\n" <tid>` 转成十六进制。
3. 在 `jstack` 输出中搜索 `nid=0x...`。
4. 观察线程名应类似 `jvm-lab-high-cpu-0`。
5. 栈顶应落在 `HighCpuDemo$BusyTask.run` 附近。

## 4. 修复方向

- 给循环增加退出条件。
- 限制线程数。
- 对真实业务场景检查正则回溯、序列化、加密计算、无限重试等热点。
