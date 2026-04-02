# HyperLogLog：为什么能用很小内存估算去重数

本文从“第一次接触 `HyperLogLog`”的角度解释一件事：

- 为什么只看哈希值中的少量“稀有模式”，就能估算集合里大概有多少个不同元素

先记一句最重要的话：

- `HyperLogLog` 不保存每个元素，只保存一组很小的寄存器；寄存器记录“我已经见过多稀有的随机模式”，再据此反推 distinct count

它解决的是：

- 近似版的 `COUNT(DISTINCT x)`
- 一共有多少个不同元素

它不解决的是：

- 某个元素是否存在
- 精确删除某个元素

当前仓库已经有一个学习向、单机内存版本的实现：

- `redis/src/main/java/yier/bubu/redis/hyperloglog/HyperLogLog.java`

本文重点仍然是概念、直觉、公式和工程边界，而不是逐行解释实现。

如果你第一次读，建议按这个顺序抓主线：

- `1 ~ 2`：先理解它要解决什么问题，以及为什么“前导零稀有模式”会和基数有关
- `3 ~ 4`：再看寄存器数组、更新过程，以及主公式怎么从这些寄存器值推出来
- `5 ~ 6`：最后看误差、分段修正和 Redis 的工程实践

## 1. 它到底在解决什么问题

典型问题是：

- 一天内有多少个不同用户访问过系统
- 一小时内出现了多少个不同 IP
- 一个日志流里大概有多少个不同请求 ID
- 一张大表某列的 distinct 值大概有多少个

精确做法很直接：

```java
Set<String> distinct = new HashSet<String>();
distinct.add(value);
long exact = distinct.size();
```

它的优点是简单、准确；代价也很直接：

- distinct 越多，`HashSet` 越大
- 几千万、几亿级别的 distinct 会带来很高的内存成本
- 分布式场景下也难合并，因为你需要合并大量具体元素

`HyperLogLog` 的态度刚好相反：

- 不保存“谁出现过”
- 只保留一个很小的统计摘要
- 接受近似值，换取固定而很小的状态

所以它是一种概率数据结构，核心价值是：

- 用固定内存估一个近似去重数
- 让 update 和 merge 都足够便宜

## 2. 为什么“前导零稀有模式”能估基数

### 2.1 为什么必须先哈希

`HyperLogLog` 的整个推导都建立在一个前提上：

- 每个元素先被映射成近似均匀随机的比特串

例如：

```text
user-1001 -> 001101011001...
user-1002 -> 101001100010...
user-1003 -> 000000111010...
```

必须先做哈希，因为原始数据通常没有“均匀随机”这个性质。比如用户 ID 往往是连续整数：

```text
1001
1002
1003
1004
```

如果直接分析这些原始值的二进制模式，那么：

- 数据分布会带着业务语义
- 不满足 HLL 所依赖的概率假设

哈希的作用，就是把“业务值”变成“近似随机样本”。只有哈希结果足够均匀，后面关于前导零概率的讨论才成立。

### 2.2 为什么前导零越多，说明样本可能越大

假设哈希值真的是均匀随机的二进制串，那么：

- 以 `1` 开头的概率是 $\frac{1}{2}$
- 以 `01` 开头的概率是 $\frac{1}{4}$
- 以 `001` 开头的概率是 $\frac{1}{8}$
- 以 `0001` 开头的概率是 $\frac{1}{16}$
- 恰好前导 $r$ 个 `0`，然后出现一个 `1` 的概率约是 $2^{-(r+1)}$

换句话说：

- 前导零越多，模式越稀有

这可以理解成一种“稀有事件探测”：

- 看 10 个不同元素，碰到极稀有模式的机会很小
- 看 100 万个不同元素，碰到更稀有模式的机会就大得多

所以：

- distinct 越大
- 越容易在哈希结果里看到更长的前导零

这就是 HLL 的根本直觉。

### 2.3 单寄存器版本：先抓住最原始的直觉

先看一个故意简化的版本：

- 只有一个寄存器
- 这个寄存器只记录目前见过的最大前导零位置

记这个值为 $R$。

如果目前见到的哈希值是：

```text
100101...
001011...
000001...
010111...
```

那么对应的前导零个数分别是：

- `0`
- `2`
- `5`
- `1`

所以当前记录为：

$$
R = 5
$$

为什么它能和 distinct count 联系起来？因为“至少达到某个稀有程度”的事件，大致会在样本量足够大时才出现。

如果把“观测到稀有程度 $R$”看成一个概率大约为 $2^{-R}$ 的事件，那么在 $n$ 个不同元素里，期望出现次数约为：

$$
n \cdot 2^{-R}
$$

当某个稀有事件“差不多开始有机会被看见一次”时，常见的量级关系就是：

$$
n \cdot 2^{-R} \approx 1
$$

于是：

$$
n \approx 2^R
$$

这不是精确值，而是一个数量级估计。

例如：

- 如果 $R = 10$，那么 $2^{10} = 1024$，说明 distinct count 大约在 `1000` 这个量级
- 如果 $R = 20$，那么 $2^{20} = 1,048,576$，说明 distinct count 大约在百万量级

### 2.4 为什么单寄存器不够好

单寄存器的问题非常明显：

- 它只看一个最大值
- 对偶然的极端样本非常敏感

如果某次运气特别好，刚好多碰到一个极稀有模式：

- 结果会被高估

如果样本刚好没碰到那个模式：

- 结果又会被低估

所以：

- 单寄存器的方向是对的
- 但波动太大，不适合工程使用

### 2.5 从一个寄存器，到很多个寄存器

HLL 的改进思路很简单：

- 不做一次“极值实验”
- 而是做很多次，再把结果综合起来

它维护很多个寄存器：

```text
M[0], M[1], ..., M[m - 1]
```

可以把这件事理解成：

- 所有元素先随机分到很多桶里
- 每个桶内部都做一次“记录最大稀有程度”的小实验

这样做的好处是：

- 某个桶偶然偏大，不至于决定整体结果
- 某个桶偶然偏小，也不至于毁掉整体结果
- 最后可以综合很多局部观测，得到更稳定的估计

### 2.6 分桶以后，每个桶到底在估什么

设：

- 全局 distinct count 是 $n$
- 一共有 $m$ 个桶

如果哈希均匀，那么元素会大致平均落到每个桶里，所以每个桶接收到的不同元素数大约是：

$$
\frac{n}{m}
$$

这一步非常关键，因为每个桶内部做的，正是前面的“单寄存器实验”。于是第 $j$ 个桶记录的寄存器值 $M_j$，大致反映的是：

$$
2^{M_j} \approx \frac{n}{m}
$$

所以：

- 单个寄存器不是在直接估全局 $n$
- 它更像是在估“自己这个桶里大概有多少不同元素”
- 这个局部量级大约是 $\frac{n}{m}$

后面公式里之所以会出现“乘回一个 $m$”，原因就在这里。

## 3. HLL 的数据结构与更新过程

### 3.1 数据结构长什么样

`HyperLogLog` 的核心状态就是一个寄存器数组：

```text
M[0], M[1], ..., M[m - 1]
```

其中：

- $m = 2^p$
- $p$ 表示用多少位来选桶

例如：

- $p = 4$ 时，$m = 16$
- $p = 10$ 时，$m = 1024$
- $p = 14$ 时，$m = 16384$

`m` 越大：

- 内存越高
- 误差越低

可以把整个结构看成一个固定长度数组：

```text
          桶号
           ↓
        0      1      2            j           m-1
     +------+------+------+--...--+------+--...--+
     | M[0] | M[1] | M[2] |       | M[j] |       |
     +------+------+------+--...--+------+--...--+
```

单个寄存器只保存一个小整数，它的含义是：

- 这是第 `j` 个桶
- `M[j]` 不是元素数，也不是元素列表
- `M[j]` 表示“这个桶当前见过的最大稀有程度”

如果从底层存储去看，这个整数当然可以再用少量 bit 编码。比如某个实现里每个寄存器用 6 bit 表示，且当前：

```text
M[j] = 5
```

它在底层内存里可能长这样：

```text
+---+---+---+---+---+---+
| 0 | 0 | 0 | 1 | 0 | 1 |
+---+---+---+---+---+---+
```

要区分两层含义：

- 逻辑上：一个桶对应一个寄存器值
- 存储上：这个寄存器值可以用若干 bit 编码

这些 bit 只是“整数 5 的二进制表示”，不是像 Bloom Filter 那样每一位都代表一个独立标志。

到这里最重要的三个事实是：

- HLL 的核心状态不是原始元素集合，而是“参数 + 寄存器数组”
- 寄存器数组长度固定，所以状态大小主要由参数决定，而不是由 distinct 个数决定
- 每个寄存器只记录对应桶里目前见过的最大稀有程度

### 3.2 处理一个元素时，HLL 做了什么

对每个元素 $x$，处理过程可以写成：

1. 计算哈希值 $h(x)$，通常使用 64 位哈希
2. 取哈希值前 $p$ 位，得到桶号 $j$
3. 对剩余位串计算 $\rho(w)$
4. 更新寄存器

公式是：

$$
M_j \leftarrow \max(M_j, \rho(w))
$$

这里 $\rho(w)$ 的定义是：

- 剩余位串里，第一个 `1` 出现的位置
- 位置从 `1` 开始计数

例如：

- `1xxxxx...`，$\rho = 1$
- `01xxxx...`，$\rho = 2$
- `001xxx...`，$\rho = 3$
- `0001xx...`，$\rho = 4$

$\rho$ 越大，说明这个模式越稀有。

### 3.3 一个手工例子：一步一步更新寄存器

下面用一个很小的玩具例子说明更新过程。假设：

- 只用 8 位哈希值演示
- 取 $p = 2$
- 所以桶数 $m = 4$
- 前 2 位选桶，后 6 位算 $\rho$

初始寄存器：

```text
M = [0, 0, 0, 0]
```

现在依次处理 6 个元素：

| 元素 | 哈希值 | 桶号前缀 | 剩余位串 | $\rho$ | 寄存器变化 |
| --- | --- | --- | --- | --- | --- |
| A | `00 101101` | `00` -> 桶 0 | `101101` | 1 | `M[0] = max(0, 1) = 1` |
| B | `00 001000` | `00` -> 桶 0 | `001000` | 3 | `M[0] = max(1, 3) = 3` |
| C | `01 000100` | `01` -> 桶 1 | `000100` | 4 | `M[1] = max(0, 4) = 4` |
| D | `10 110000` | `10` -> 桶 2 | `110000` | 1 | `M[2] = max(0, 1) = 1` |
| E | `11 011111` | `11` -> 桶 3 | `011111` | 2 | `M[3] = max(0, 2) = 2` |
| F | `11 000010` | `11` -> 桶 3 | `000010` | 5 | `M[3] = max(2, 5) = 5` |

最后得到：

```text
M = [3, 4, 1, 5]
```

这 4 个数字还不是 distinct count，但已经是一份压缩摘要：

- 桶 0 见过稀有程度约为 `3` 的模式
- 桶 1 见过更稀有的模式，到了 `4`
- 桶 2 只见过比较普通的模式，只有 `1`
- 桶 3 则见过很稀有的模式，到了 `5`

### 3.4 为什么重复元素通常不会把结果不断冲大

同一个元素重复出现时：

- 哈希值不会变
- 桶号不会变
- $\rho$ 也不会变

于是最多只是重复执行：

```text
M[j] = max(M[j], 同一个值)
```

寄存器通常不会继续变大。所以 HLL 天然对重复元素不敏感，这也是它适合做 distinct count 的原因之一。

### 3.5 到这里先记住三件事

先不要急着背公式，先抓住这三点：

1. 长前导零是稀有事件
2. 样本越多，越容易碰到更稀有的事件
3. 每个桶都在独立记录“我这里见过多稀有的模式”

后面的公式，本质上只是把这三件事变成一个稳定的估算器。

## 4. 主公式是怎么从寄存器数组推出来的

### 4.1 从寄存器值到局部规模信号

对第 $j$ 个桶来说：

- 它看到的不同元素数大致是 $\frac{n}{m}$

而单寄存器直觉告诉我们：

- 如果最大稀有程度是 $M_j$
- 那么该桶内的 distinct 数量级可以粗略看成 $2^{M_j}$

所以可以定义：

$$
x_j = 2^{M_j}
$$

并把 $x_j$ 理解为：

- 第 $j$ 个桶对“局部 distinct count”的粗略估计

这些局部估计大致都在估：

$$
\frac{n}{m}
$$

### 4.2 为什么不用算术平均，而要用调和平均

如果已经有很多个局部估计 $x_j$，最自然的想法是：

- 直接做平均

但算术平均对偏大的极端值非常敏感，而 HLL 的寄存器恰好容易出现这种偏大值：

- 某个桶可能只是运气好，刚好碰到一个非常稀有的模式

算术平均会被这种桶明显拉高。

HLL 选择的是调和平均：

$$
\operatorname{HM}(x_1,\ldots,x_m) = \frac{m}{\sum_{j=1}^{m} \frac{1}{x_j}}
$$

调和平均的直觉是：

- 先取倒数
- 对倒数做算术平均
- 再整体取倒数

这样做的效果是：

- 偏大的离群值在取倒数后会变成很小的数
- 它们对整体结果的影响会被削弱

看一个简单例子：

$$
x = [4,\,4,\,4,\,64]
$$

算术平均是：

$$
\operatorname{AM} = \frac{4 + 4 + 4 + 64}{4} = 19
$$

调和平均是：

$$
\operatorname{HM}
= \frac{4}{\frac{1}{4} + \frac{1}{4} + \frac{1}{4} + \frac{1}{64}}
= \frac{4}{0.765625}
\approx 5.22
$$

`64` 仍然会影响结果，但影响显著小于算术平均。

这正符合 HLL 的需求：

- 它希望综合很多桶的局部规模信号
- 但不希望被少数偶然偏大的桶牵着走

因为这里 $x_j = 2^{M_j}$，所以：

$$
\frac{1}{x_j} = 2^{-M_j}
$$

于是调和平均可以写成：

$$
\operatorname{HM} = \frac{m}{\sum_{j=0}^{m-1} 2^{-M_j}}
$$

这就是为什么主公式里会出现：

$$
\sum_{j=0}^{m-1} 2^{-M_j}
$$

它不是凭空冒出来的，而是来自“先把每个桶看成一个局部估计，再用调和平均聚合”。

### 4.3 从调和平均走到经典公式

上一节得到的调和平均，估的是：

- 每个桶里的平均 distinct 数量级
- 也就是大约在估 $\frac{n}{m}$

而我们真正要的是全局 distinct count $n$，所以要再乘一个 $m$：

$$
n \approx m \cdot \operatorname{HM}
$$

代入调和平均：

$$
n \approx m \cdot \frac{m}{\sum_{j=0}^{m-1} 2^{-M_j}}
= \frac{m^2}{\sum_{j=0}^{m-1} 2^{-M_j}}
$$

真实统计分布还会带来系统性偏差，因此再乘一个校正常数 $\alpha_m$：

$$
E = \alpha_m \cdot \frac{m^2}{\sum_{j=0}^{m-1} 2^{-M_j}}
$$

这就是经典的 HLL 估计式。

### 4.4 $\alpha_m$ 是什么

$\alpha_m$ 不是魔法，只是一个偏差校正系数。常见取值是：

- $m = 16$ 时，$\alpha_m = 0.673$
- $m = 32$ 时，$\alpha_m = 0.697$
- $m = 64$ 时，$\alpha_m = 0.709$
- 更大时常用近似：

$$
\alpha_m \approx \frac{0.7213}{1 + \frac{1.079}{m}}
$$

你不需要背这些常数，但要知道：

- 原始估算器不是完全无偏
- $\alpha_m$ 的作用就是把系统偏差拉回去

### 4.5 现在回头看主公式

重新读一遍：

$$
E = \alpha_m \cdot \frac{m^2}{\sum_{j=0}^{m-1} 2^{-M_j}}
$$

可以把它拆成一条很清晰的链：

1. $M_j$：第 $j$ 个桶里见过的最大稀有程度
2. $2^{M_j}$：第 $j$ 个桶对局部 distinct 数量级的估计
3. $\sum 2^{-M_j}$：对这些局部估计做调和平均
4. $m^2$：把“单桶平均”扩回全局规模
5. $\alpha_m$：做偏差修正

如果这条链打通了，公式就不再像“从天上掉下来”。

## 5. 手算、误差与分段修正

### 5.1 用一组具体寄存器值手算一次

下面拿一组 16 桶的玩具寄存器数组，完整走一遍计算过程：

```text
M = [3, 5, 2, 4, 4, 1, 6, 3, 2, 5, 6, 4, 3, 2, 5, 4]
```

这里：

- $m = 16$
- 所以 $m^2 = 256$
- 对应 $\alpha_m = 0.673$

#### 第一步：算 $\sum 2^{-M_j}$

按寄存器值分组更容易手算：

- `1` 出现 1 次
- `2` 出现 3 次
- `3` 出现 3 次
- `4` 出现 4 次
- `5` 出现 3 次
- `6` 出现 2 次

所以：

$$
\begin{aligned}
\sum_{j=0}^{m-1} 2^{-M_j}
&= 1 \cdot 2^{-1} + 3 \cdot 2^{-2} + 3 \cdot 2^{-3} + 4 \cdot 2^{-4} + 3 \cdot 2^{-5} + 2 \cdot 2^{-6} \\
&= 0.5 + 0.75 + 0.375 + 0.25 + 0.09375 + 0.03125 \\
&= 2.0
\end{aligned}
$$

#### 第二步：先算未校正估计值

$$
\frac{m^2}{\sum 2^{-M_j}} = \frac{256}{2.0} = 128
$$

这个 `128` 表示：

- 如果先不做偏差修正，这组寄存器给出的估计大约是 `128`

#### 第三步：乘上 $\alpha_m$

$$
E = 0.673 \cdot 128 = 86.144
$$

所以最终估计大约是：

$$
\operatorname{distinct\_count} \approx 86
$$

#### 第四步：判断是否需要小范围修正

经典 HLL 在小基数区间通常还会看：

- 当前估计值是否比较小
- 空桶是否很多

这个玩具例子里：

- 寄存器数组没有 `0`
- 估计值也已经到了几十量级

所以不触发那套“空桶很多”的小范围修正逻辑，结果就停在 `86` 左右。

### 5.2 为什么误差近似为 $\frac{1.04}{\sqrt{m}}$

HLL 的标准误差常写成：

$$
\operatorname{SE} \approx \frac{1.04}{\sqrt{m}}
$$

这意味着：

- $m = 16$，误差大约是 `26%`
- $m = 1024$，误差大约是 `3.25%`
- $m = 16384$，误差大约是 `0.81%`

它告诉我们两件事：

- 增加寄存器，确实能降低误差
- 但收益是平方根级别，不是线性级别

也就是说：

- 想把误差减半，往往需要把寄存器数提高到原来的 4 倍

所以 HLL 的强项是：

- 用很小的内存拿到“足够好”的估计

它不是：

- 用一点点内存拿到几乎零误差

### 5.3 为什么它的内存会这么省

假设：

- $p = 14$
- 那么 $m = 2^{14} = 16384$

此时它只需要维护 `16384` 个寄存器，而每个寄存器保存的只是一个很小的整数：

- 当前桶见过的最大 $\rho$

所以整个结构的内存通常只有十几 KB 量级。

和精确去重相比，内存模型完全不同：

- 精确去重更像“distinct 越多，状态越大”
- HLL 更像“状态大小基本固定，只由精度参数决定”

### 5.4 小基数时为什么不能只用主公式

如果 distinct count 很小，往往会出现一个明显现象：

- 许多桶仍然是空的
- 也就是很多寄存器仍然为 `0`

这时如果硬套主公式，结果往往不够准。原因很直观：

- 数据很少时，“空桶还有多少”本身就是非常强的信息
- 如果继续只看极端稀有模式，会浪费掉这个信号

所以经典 HLL 在小范围通常会改用 `Linear Counting` 修正。设：

- $V$ 表示仍然为 `0` 的寄存器个数

则常见小范围估计是：

$$
E_{\text{small}} = m \ln\!\left(\frac{m}{V}\right)
$$

它的直觉是：

- 空桶很多，说明总元素还不多
- 空桶越来越少，说明 distinct 正在上升
- 小样本区间里，“桶占用率”往往比“极端稀有模式”更可靠

所以真实实现往往是分段的：

- 小基数区间：更相信空桶信息
- 中间区间：更相信主公式
- 很大区间：再做额外修正

### 5.5 大基数时为什么还会出问题

另一端也有问题。

如果哈希位数有限，比如只有 32 位，那么 distinct count 非常大时：

- 哈希空间会逐渐拥挤
- 哈希冲突会越来越不可忽略

这时估计也会受到影响。所以原始 HLL 论文除了讨论小范围修正，也讨论了大范围修正。

现代工程实现通常会做这些增强：

- 使用 64 位哈希
- 做经验偏差修正
- 在小数据量时使用稀疏表示

这类增强版本通常被称为：

- `HyperLogLog++`

## 6. Redis 的工程实践

前面讲的是原理和公式，这一节只看一个更现实的问题：

- Redis 怎么把 `HyperLogLog` 做成可在线服务的原生能力

下面的代码块直接基于 Redis `src/hyperloglog.c` 的真实函数名和调用关系，只保留核心逻辑，并补少量中文注释。

### 6.1 固定参数 + 默认 sparse 起步

Redis 不是让业务自己维护寄存器数组，而是把 HLL 做成了有固定参数的一种原生字符串值。

从源码定义可以直接看出它的默认工程参数：

```c
#define HLL_P 14
#define HLL_BITS 6
#define HLL_DENSE_SIZE (HLL_HDR_SIZE + ((HLL_REGISTERS * HLL_BITS + 7) / 8))
```

这几行对应的含义就是：

- `p = 14`
- 寄存器数是 `16384`
- 每个寄存器 `6 bit`
- dense 形态的数据区大约是 `12 KB`

Redis 新建 HLL 时默认并不直接进入 dense，而是从 sparse 开始：

```c
robj *createHLLObject(void) {
    o = createObject(OBJ_STRING,s);
    hdr = o->ptr;
    memcpy(hdr->magic,"HYLL",4);
    hdr->encoding = HLL_SPARSE; /* 新对象默认从 sparse 起步 */
    return o;
}
```

这背后的工程意图很明确：

- 小基数 key 很多时，先用更省空间的表示
- 基数变大后，再升级到更适合高频更新和统计的 dense

### 6.2 `PFADD` 的核心不是“存元素”，而是“更新寄存器”

Redis 写入 HLL 时，先把元素哈希，再定位寄存器，再更新最大稀有程度。源码里的核心路径可以压成：

```c
int hllPatLen(unsigned char *ele, size_t elesize, long *regp) {
    hash = MurmurHash64A(ele,elesize,0xadc83b19ULL);
    index = hash & HLL_P_MASK;          /* 低 P 位决定寄存器 */
    hash >>= HLL_P;                     /* 剩余位用来算 zero-run */
    hash |= ((uint64_t)1<<HLL_Q);       /* 保证计数一定终止 */
    count = __builtin_ctzll(hash) + 1;  /* 尾随零个数 + 1 */
    *regp = (int) index;
    return count;
}

int hllDenseAdd(uint8_t *registers, unsigned char *ele, size_t elesize) {
    long index;
    uint8_t count = hllPatLen(ele,elesize,&index);
    return hllDenseSet(registers,index,count); /* 只在更大时更新 */
}
```

这正对应前面讲过的那条主线：

- 哈希
- 分桶
- 算 $\rho$
- 用更大的寄存器值覆盖旧值

命令层的 `PFADD` 也只是围绕这条路径组织起来的：

```c
void pfaddCommand(client *c) {
    kvobj *kv = lookupKeyWriteWithLink(c->db,c->argv[1],&link);

    if (kv == NULL) {
        robj *o = createHLLObject();
        kv = dbAddByLink(c->db,c->argv[1],&o,&link);
        updated++;
    } else {
        if (isHLLObjectOrReply(c,kv) != C_OK) return;
        kv = dbUnshareStringValue(c->db,c->argv[1],kv);
    }

    for (j = 2; j < c->argc; j++) {
        int retval = hllAdd(kv,(unsigned char*)c->argv[j]->ptr,
                               sdslen(c->argv[j]->ptr));
        if (retval == 1) updated++;
    }

    if (updated) HLL_INVALIDATE_CACHE(hdr); /* 写入后让 count 缓存失效 */
}
```

这里最重要的工程点有两个：

- 新 key 默认以 sparse 编码创建
- 只要寄存器被改动，就让 cached cardinality 失效

所以 `PFADD` 的真实语义不是“精确加入了一个新元素”，而是：

- 这次输入是否让 HLL 的内部寄存器发生了有效变化

### 6.3 `PFCOUNT` 为什么单 key 快，多 key 慢

Redis 的单 key `PFCOUNT` 快，是因为它会缓存基数估计值；多 key `PFCOUNT` 慢，是因为它必须现场 merge。

单 key 路径的核心逻辑可以压成：

```c
if (HLL_VALID_CACHE(hdr)) {
    card = (uint64_t)hdr->card[0];
    card |= (uint64_t)hdr->card[1] << 8;
    ...
    card |= (uint64_t)hdr->card[7] << 56;
} else {
    card = hllCount(hdr,&invalid);
    hdr->card[0] = card & 0xff;
    ...
    hdr->card[7] = (card >> 56) & 0xff;
    keyModified(c,c->db,c->argv[1],o,1); /* 读命令会回写缓存 */
}
```

多 key 路径则完全不同：

```c
if (c->argc > 2) {
    uint8_t max[HLL_HDR_SIZE+HLL_REGISTERS], *registers;
    memset(max,0,sizeof(max));
    hdr = (struct hllhdr*) max;
    hdr->encoding = HLL_RAW; /* 临时原始寄存器数组 */
    registers = max + HLL_HDR_SIZE;

    for (j = 1; j < c->argc; j++) {
        kvobj *o = lookupKeyRead(c->db,c->argv[j]);
        if (o == NULL) continue;
        if (isHLLObjectOrReply(c,o) != C_OK) return;
        if (hllMerge(registers,o) == C_ERR) return;
    }
    addReplyLongLong(c,hllCount(hdr,NULL));
    return;
}
```

这里的 `HLL_RAW` 是 Redis 专门给多 key `PFCOUNT` 准备的内部表示：

- 临时 union 结果直接按每寄存器 1 字节展开
- 避免先压回 6 bit dense 再统计

而 `hllCount()` 本身也不是只会处理一种表示，它会先按表示类型统计寄存器 histogram，再统一估算：

```c
if (hdr->encoding == HLL_DENSE)
    hllDenseRegHisto(hdr->registers,reghisto);
else if (hdr->encoding == HLL_SPARSE)
    hllSparseRegHisto(hdr->registers,sdslen((sds)hdr)-HLL_HDR_SIZE,invalid,reghisto);
else if (hdr->encoding == HLL_RAW)
    hllRawRegHisto(hdr->registers,reghisto);
E = llroundl(HLL_ALPHA_INF * m * m / z);
```

所以从源码能直接看出：

- 单 key `PFCOUNT` 快，核心在缓存
- 多 key `PFCOUNT` 慢，核心在 on-the-fly merge + 重新统计

这也解释了为什么业务上更适合：

- 高频查询单 key
- 对高频并集查询做预聚合，而不是每次临时 union

### 6.4 `PFMERGE` 的核心就是逐寄存器取 `max`

Redis 的 `PFMERGE` 并没有更神秘的逻辑，它的核心就是把多个 HLL 按寄存器位取最大值，再写回目标对象：

```c
void pfmergeCommand(client *c) {
    uint8_t max[HLL_REGISTERS];
    int use_dense = 0;

    memset(max,0,sizeof(max));
    for (j = 1; j < c->argc; j++) {
        kvobj *o = lookupKeyRead(c->db,c->argv[j]);
        if (o == NULL) continue;

        hdr = o->ptr;
        if (hdr->encoding == HLL_DENSE) use_dense = 1; /* 任一源是 dense，目标尽快转 dense */
        if (hllMerge(max,o) == C_ERR) return;
    }

    if (use_dense && hllSparseToDense(kv) == C_ERR) return;

    if (use_dense) {
        hdr = kv->ptr;
        hllDenseCompress(hdr->registers,max);
    } else {
        for (j = 0; j < HLL_REGISTERS; j++) {
            if (max[j] == 0) continue;
            hdr = kv->ptr;
            switch (hdr->encoding) {
                case HLL_DENSE: hllDenseSet(hdr->registers,j,max[j]); break;
                case HLL_SPARSE: hllSparseSet(kv,j,max[j]); break;
            }
        }
    }

    hdr = kv->ptr;
    HLL_INVALIDATE_CACHE(hdr); /* merge 后缓存失效 */
}
```

这里体现出 Redis 的几个关键工程取舍：

1. merge 是主路径能力，所以 `PFMERGE` 被做成原生命令
2. 目标对象是否转 dense，不是拍脑袋决定，而是看输入里是否已经存在 dense
3. merge 后立刻让缓存失效，保证后续 `PFCOUNT` 会重建正确结果

这会直接影响使用方式：

- 明细 key 可以按小时、按分片写入
- 日级、周级、全站 UV 这类高频结果更适合提前 `PFMERGE`
- 查询路径尽量命中已经预聚合好的单个 key

### 6.5 从 Redis 源码看，它优化的到底是什么

把这一整节压成一句话，Redis 真正优化的不是“把公式写出来”，而是把 HLL 变成一个可以长期运行的系统组件。源码里能直接看到它在优化这些点：

- 固定参数下的误差和内存上界
- sparse / dense 双表示带来的空间效率
- `PFADD` 的低成本更新路径
- 单 key `PFCOUNT` 的缓存命中
- 多 key `PFCOUNT` 的临时 `HLL_RAW` 加速路径
- `PFMERGE` 驱动的预聚合能力

如果你想继续对照一手资料，主要看这两个入口：

- Redis HyperLogLog 文档：<https://redis.io/docs/latest/develop/data-types/probabilistic/hyperloglogs/>
- Redis 源码 `src/hyperloglog.c`：<https://github.com/redis/redis/blob/unstable/src/hyperloglog.c>
