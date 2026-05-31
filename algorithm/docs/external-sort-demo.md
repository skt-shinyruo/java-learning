# 外部排序：分块排序与多路归并

`ExternalSortDemo` 演示的是最典型的外部归并排序：当数据不能一次性放进内存时，先把输入文件拆成多个能放进内存的小块，分别排序并写成临时有序文件，再把这些有序文件归并成最终结果。

这个案例只处理一种简单格式：

- 输入文件每行一个整数
- 输出文件每行一个排序后的整数
- `chunkSize` 表示每次最多读入内存的整数个数

入口方法是：

```java
public static void sort(Path input, Path output, Path tempDir, int chunkSize) throws IOException
```

---

## 1. 整体流程

外部排序分成两个阶段。

第一阶段是“分块排序”：

1. 从输入文件逐行读取整数
2. 每读满 `chunkSize` 个整数，就在内存中排序
3. 把这个有序块写入 `tempDir` 下的临时 run 文件
4. 输入读完后，如果还有不足一个块的数据，也排序并写成最后一个 run 文件

第二阶段是“多路归并”：

1. 打开所有临时 run 文件
2. 从每个 run 文件读取第一个整数
3. 把这些整数放进小顶堆 `PriorityQueue`
4. 每次取出堆顶的最小值写入输出文件
5. 从这个最小值所属的 run 文件继续读取下一个整数，再放回堆中
6. 重复直到所有 run 文件都读完

最后在 `finally` 中删除本次生成的临时 run 文件。

---

## 2. 为什么要先写 run 文件

假设输入文件是：

```text
9
1
5
3
7
2
```

如果 `chunkSize = 2`，程序一次最多只能把 2 个整数放进内存。第一阶段会生成三个有序 run：

```text
run-1: 1, 9
run-2: 3, 5
run-3: 2, 7
```

这些 run 文件都已经局部有序，但它们合在一起还不是全局有序。所以第二阶段需要把多个有序序列归并成一个完整有序序列：

```text
1, 2, 3, 5, 7, 9
```

这就是外部排序的核心思路：内存只负责处理一个小块或每个 run 的当前值，完整数据保存在磁盘文件中。

---

## 3. `splitAndSortRuns` 做了什么

`splitAndSortRuns` 负责第一阶段。

```java
List<Integer> chunk = new ArrayList<Integer>(chunkSize);
```

`chunk` 是当前内存块。程序逐行读取输入文件，把每一行转成整数：

```java
chunk.add(Integer.parseInt(line.trim()));
```

当 `chunk.size() == chunkSize` 时，说明这个块已经满了，调用 `writeRun`：

```java
runs.add(writeRun(chunk, tempDir));
chunk.clear();
```

`writeRun` 会先用 `Collections.sort(values)` 排序，再写入一个临时文件：

```java
Path run = Files.createTempFile(tempDir, "external-sort-run-", ".txt");
```

方法返回的是所有 run 文件的路径列表，供第二阶段归并使用。

---

## 4. `mergeRuns` 为什么用 `PriorityQueue`

每个 run 文件本身都是有序的。归并时，不需要把所有 run 文件的数据重新读入内存，只需要知道“每个 run 当前还没输出的最小值”。

`RunEntry` 保存两件事：

- `value`：当前候选整数
- `reader`：这个整数来自哪个 run 文件

小顶堆里最多同时保存 `run` 文件数量个元素。每次：

1. `heap.poll()` 取出当前最小值
2. 把这个最小值写入输出文件
3. 从它所属的 `RunReader` 读取下一个整数
4. 如果还有下一个整数，就放回堆里继续比较

这比每次手动扫描所有 run 文件更清晰，也更符合多路归并的常见写法。

---

## 5. 空文件和异常处理

如果输入文件为空，第一阶段不会生成任何 run 文件。第二阶段仍会创建输出文件，只是不会写入任何内容，因此输出文件也是空文件。

`chunkSize <= 0` 没有意义，因为它表示一次最多读入 0 个或负数个整数。代码会直接抛出：

```java
throw new IllegalArgumentException("chunkSize must be positive");
```

文件读写错误继续向外抛出 `IOException`。整数格式错误由 `Integer.parseInt` 抛出标准异常。这个 demo 不额外包装异常，目的是让核心排序流程更清楚。

---

## 6. 临时文件清理

`sort` 方法把清理逻辑放在 `finally` 中：

```java
try {
    runs = splitAndSortRuns(input, tempDir, chunkSize);
    mergeRuns(runs, output);
} finally {
    deleteRuns(runs);
}
```

这样即使归并阶段抛出异常，已经生成的临时 run 文件也会被尝试删除。

`deleteRuns` 会逐个调用 `Files.deleteIfExists(run)`。如果多个文件删除失败，代码保留第一个异常，并把后续异常通过 `addSuppressed` 挂到第一个异常上，避免直接丢失错误信息。

---

## 7. 复杂度和内存占用

设总数据量为 `n`，临时 run 文件数量为 `k`。

- 分块排序阶段：每个块在内存中排序，块大小最多是 `chunkSize`
- 归并阶段：每输出一个整数，都要做一次堆操作，复杂度约为 `O(n log k)`
- 内存占用：主要是一个 `chunk` 加上归并堆，约为 `O(chunkSize + k)`

真实生产环境会继续优化 run 数量、缓冲区大小、临时文件目录和异常恢复策略。这个案例刻意保持简单，重点是把“分块排序 + 多路归并”的主流程讲清楚。

---

## 8. 对应测试

`ExternalSortDemoTest` 覆盖了几个关键场景：

- 输入乱序整数，使用较小 `chunkSize` 强制生成多个 run 文件
- 重复值和负数保持正确顺序
- 空输入生成空输出文件
- 非法 `chunkSize` 被拒绝
- 排序完成后临时 run 文件被清理

可以只运行这个案例的测试：

```bash
mvn -pl algorithm -Dtest=ExternalSortDemoTest test
```

也可以运行整个算法模块：

```bash
mvn -pl algorithm test
```
