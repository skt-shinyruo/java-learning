# 汉诺塔：递归与手动模拟函数调用栈

这篇文档的目标不是只给出汉诺塔答案，而是把一个更重要的问题讲清楚：

- 递归版汉诺塔为什么这么写
- 递归调用栈里到底保存了什么
- 如何用 `Deque<Frame>` 手动模拟函数调用栈
- `state` 这个字段分别表示什么

如果只想先记住一句话，可以记这个版本：

- 递归不是魔法，本质上是 JVM 帮你维护调用栈；手动改成非递归时，需要自己保存“参数、局部变量、执行到哪一步”这些栈帧信息。

---

## 1. 先看递归版

汉诺塔的规则可以拆成三步：

1. 把 `n - 1` 个盘子从 `from` 借助 `to` 移到 `aux`
2. 把第 `n` 个盘子从 `from` 移到 `to`
3. 把 `n - 1` 个盘子从 `aux` 借助 `from` 移到 `to`

对应的递归代码是：

```java
public class HanoiRecursiveDemo {

    public static void main(String[] args) {
        hanoi(3, 'A', 'B', 'C');
    }

    private static void hanoi(int n, char from, char aux, char to) {
        if (n == 1) {
            System.out.println("把第1个盘子从 " + from + " 移到 " + to);
            return;
        }

        hanoi(n - 1, from, to, aux);
        System.out.println("把第" + n + "个盘子从 " + from + " 移到 " + to);
        hanoi(n - 1, aux, from, to);
    }
}
```

这里最关键的是：每次调用 `hanoi()` 时，JVM 都会创建一个新的调用栈帧，保存当前调用的参数和执行位置。

---

## 2. 函数调用栈里需要保存什么

要把递归改成手动模拟调用栈，就要先明确一个递归调用至少需要哪些信息。

对 `hanoi(n, from, aux, to)` 来说，每个栈帧需要保存：

- `n`：当前要移动多少个盘子
- `from`：起始柱
- `aux`：辅助柱
- `to`：目标柱
- `state`：当前这个函数执行到哪一步了

`state` 可以理解成“返回地址”或“程序计数位置”。递归函数被子调用打断后，回来时必须知道下一步该继续执行哪里。

在汉诺塔里，一个非叶子调用大致有三个阶段：

```text
0
  -> 先调用左递归 hanoi(n - 1, from, to, aux)

1
  -> 左递归返回后，移动第 n 个盘子
  -> 再调用右递归 hanoi(n - 1, aux, from, to)

2
  -> 右递归返回后，当前函数结束
```

对应关系可以这样记：

- `stack.push(...)`：模拟调用子函数
- `stack.peek()`：查看当前正在执行的栈帧
- `stack.pop()`：模拟函数返回
- `state`：表示当前栈帧执行到哪一步

---

## 3. 非递归版：手动模拟调用栈

下面这个版本不是直接模拟三根柱子的移动规律，而是专门模拟递归函数的调用栈。

```java
import java.util.ArrayDeque;
import java.util.Deque;

public class HanoiIterativeDemo {

    private static class Frame {
        int n;
        char from;
        char aux;
        char to;
        int state; // 0: 先处理左递归  1: 输出移动  2: 处理右递归

        Frame(int n, char from, char aux, char to) {
            this.n = n;
            this.from = from;
            this.aux = aux;
            this.to = to;
        }
    }

    public static void main(String[] args) {
        hanoi(3, 'A', 'B', 'C');
    }

    public static void hanoi(int n, char from, char aux, char to) {
        if (n <= 0) {
            return;
        }

        Deque<Frame> stack = new ArrayDeque<Frame>();
        stack.push(new Frame(n, from, aux, to));

        while (!stack.isEmpty()) {
            Frame f = stack.peek();

            if (f.n == 1) {
                System.out.println("把第1个盘子从 " + f.from + " 移到 " + f.to);
                stack.pop();
                continue;
            }

            if (f.state == 0) {
                f.state = 1;
                stack.push(new Frame(f.n - 1, f.from, f.to, f.aux));
            } else if (f.state == 1) {
                System.out.println("把第" + f.n + "个盘子从 " + f.from + " 移到 " + f.to);
                f.state = 2;
                stack.push(new Frame(f.n - 1, f.aux, f.from, f.to));
            } else {
                stack.pop();
            }
        }
    }
}
```

这个版本和递归版是一一对应的：

```text
hanoi(n - 1, from, to, aux)
```

对应：

```java
stack.push(new Frame(f.n - 1, f.from, f.to, f.aux));
```

而递归函数里的 `return`，对应：

```java
stack.pop();
```

---

## 4. 所有递归都能这样改吗

原则上，大多数普通递归都可以改成显式栈版本。

因为递归本质上依赖的就是调用栈。只要能把每次调用需要的信息保存下来，就能自己用数据结构模拟它。

常见情况可以分成几类：

- 尾递归：通常可以直接改成 `while` 循环，不一定需要显式栈
- 普通递归：通常需要 `Frame + state` 保存执行阶段
- 多分支递归：每个分支返回后要恢复的位置更多，`state` 会更复杂
- 互相递归：也能改，但 `Frame` 里可能还需要保存当前正在模拟哪个函数

需要注意的是，手动模拟调用栈并不一定更省内存。

它只是把 JVM 调用栈里的信息搬到了堆上的 `Deque<Frame>` 里：

- 好处：可以避免递归太深导致 `StackOverflowError`
- 代价：代码更复杂，可读性通常不如递归版

所以工程上不必把所有递归都改成非递归。只有当递归深度可能很大、或者明确想学习调用栈机制时，手动模拟调用栈才更有价值。

---

## 5. 小结

汉诺塔适合用来理解递归，也适合用来理解“如何把递归改成显式栈”。

关键不是记住代码，而是记住这个映射关系：

```text
递归调用        -> stack.push(new Frame(...))
当前函数栈帧    -> stack.peek()
函数返回        -> stack.pop()
执行位置        -> state
```

一旦理解了这个映射，树遍历、深度优先搜索、分治算法里的很多递归，都可以用类似方式手动展开。
