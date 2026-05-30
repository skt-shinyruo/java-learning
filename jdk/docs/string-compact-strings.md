# Java String：coder、LATIN1、UTF16 与 Unicode

这篇文档解释 JDK 9 之后 `String` / `StringBuilder` 内部的 Compact Strings 设计，重点回答几个容易混在一起的问题：

- `StringBuilder` 里的 `coder` 是什么
- 以前 `String` 使用 `char[]`，每个 `char` 固定 2 字节是什么意思
- `LATIN1` 和 `UTF16` 在存储上到底有什么区别
- `LATIN1` / `UTF16` 和 Unicode 是什么关系

如果先记一句话，可以先记这个版本：

- Java `String` 对外表达的是 Unicode 文本；JDK 9 之后，内部会根据内容选择用 `LATIN1` 一字符 1 字节存，还是用 `UTF16` 一个 Java `char` 2 字节存

---

## 1. `coder` 是什么

JDK 9 之后，`String` 和 `AbstractStringBuilder` 的内部存储不再只是 `char[]`，而是类似下面这种结构：

```java
byte[] value;
byte coder;
```

其中 `value` 是真正保存字符数据的字节数组，`coder` 用来说明这些字节应该按哪种方式解释。

`coder` 通常有两个值：

```java
static final byte LATIN1 = 0;
static final byte UTF16  = 1;
```

含义是：

- `LATIN1`：内容中的每个 Java `char` 都能用 1 个字节表示
- `UTF16`：内容中存在不能用 `LATIN1` 表示的字符，需要按 UTF-16 的形式存储

例如 `AbstractStringBuilder(String str)` 构造方法里有类似逻辑：

```java
int length = str.length();
int capacity = (length < Integer.MAX_VALUE - 16)
        ? length + 16 : Integer.MAX_VALUE;
final byte initCoder = str.coder();
coder = initCoder;
value = (initCoder == LATIN1)
        ? new byte[capacity] : StringUTF16.newBytesFor(capacity);
append(str);
```

这段代码的意思是：

1. 先看传入的 `String str` 当前内部是 `LATIN1` 还是 `UTF16`
2. `StringBuilder` 也用相同的 `coder` 初始化
3. 如果是 `LATIN1`，底层 `byte[]` 长度就是 `capacity`
4. 如果是 `UTF16`，底层 `byte[]` 通常需要 `capacity * 2` 个字节

所以 `coder` 的核心作用是告诉 JDK：

- 读取 `value` 时，是按 1 字节一组读
- 还是按 2 字节一组读

---

## 2. 以前 `char[]` 固定 2 字节是什么意思

JDK 8 及以前，`String` 内部大致是这样存的：

```java
private final char[] value;
```

Java 的 `char` 是 16 位，也就是 2 字节。

所以字符串：

```java
"abc"
```

内部可以理解成：

```java
char[] value = {'a', 'b', 'c'};
```

虽然 `a`、`b`、`c` 用 1 字节就足够表示，但在 `char[]` 里每个元素仍然占 2 字节：

```text
3 个 char * 2 字节 = 6 字节
```

中文也是一样：

```java
"你好"
```

内部可以理解成：

```java
char[] value = {'你', '好'};
```

占用大约：

```text
2 个 char * 2 字节 = 4 字节
```

所以“以前每个字符固定 2 字节”更准确地说是：

- 以前 `String` 内部每个 Java `char` 都按 2 字节存
- 英文字符也不例外

需要注意：Java 的 `char` 是 UTF-16 code unit，不一定等于一个完整 Unicode 字符。比如 emoji：

```java
"😀".length() // 2
```

这个字符在 Java 里需要两个 `char`，也就是 4 字节。

### 2.1 包含 emoji 的 `length()` 和 `charAt()` 示例

假设有这样一个字符串：

```java
String s = "abcdefghij😀klmnopqrst";
```

它包含：

```text
emoji 前：10 个英文字母 abcdefghij
emoji 后：10 个英文字母 klmnopqrst
```

这个字符串的 Java `length()` 是：

```java
s.length() // 22
```

原因是：

```text
10 个英文 char
+ emoji 占 2 个 char
+ 10 个英文 char
= 22 个 Java char
```

如果按 Unicode 码点数算，则是：

```java
s.codePointCount(0, s.length()) // 21
```

因为它实际包含：

```text
20 个英文字母 + 1 个 emoji = 21 个 Unicode 码点
```

在 JDK 9+ 的内部存储里，因为字符串包含 emoji，整个 `String` 不能用 `LATIN1`，会用 `UTF16`。所以前后的英文字母也会按 2 字节存：

```text
22 个 char * 2 字节 = 44 字节
```

这是底层字符数据的字节数，不包含 `String` 对象头、`byte[]` 数组对象头、对齐填充等 JVM 对象开销。

如果把同一段文本编码成 UTF-8 字节，则是：

```text
10 个英文 * 1 字节
+ emoji * 4 字节
+ 10 个英文 * 1 字节
= 24 字节
```

再看 Java `char` 下标：

```text
index 0  -> a
index 1  -> b
...
index 8  -> i
index 9  -> j
index 10 -> emoji 的高代理项 \uD83D
index 11 -> emoji 的低代理项 \uDE00
index 12 -> k
index 13 -> l
...
```

所以：

```java
s.charAt(9)  // 'j'
s.charAt(10) // '\uD83D'，高代理项，不是完整 emoji
s.charAt(11) // '\uDE00'，低代理项，不是完整 emoji
s.charAt(12) // 'k'
```

如果分别打印：

```java
System.out.println(s.charAt(9));
System.out.println(s.charAt(10));
System.out.println(s.charAt(11));
System.out.println(s.charAt(12));
```

概念上分别是：

```text
j
高代理项，单独打印通常会显示成 ?、乱码或不可见字符
低代理项，单独打印通常会显示成 ?、乱码或不可见字符
k
```

但如果把第 10 和第 11 两个 `char` 连在一起打印：

```java
System.out.println("" + s.charAt(10) + s.charAt(11));
```

才会组成完整的：

```text
😀
```

---

## 3. `LATIN1` 和 `UTF16` 的存储差异

在 JDK 9+ 的 Compact Strings 设计里，存储差异可以先理解成：

```text
LATIN1：一个 Java char 用 1 个 byte 存
UTF16 ：一个 Java char 用 2 个 byte 存
```

例如：

```java
String s = "ABC";
```

`A`、`B`、`C` 的 Unicode 码点分别是：

```text
A = U+0041
B = U+0042
C = U+0043
```

这些值都小于等于 `U+00FF`，所以可以用 `LATIN1` 存：

```text
value = [0x41, 0x42, 0x43]
coder = LATIN1
```

只需要 3 个字节。

如果按 `UTF16` 存，概念上会是：

```text
value = [0x00, 0x41, 0x00, 0x42, 0x00, 0x43]
coder = UTF16
```

需要 6 个字节。

再看中文：

```java
String s = "你好";
```

对应的 Unicode 码点是：

```text
你 = U+4F60
好 = U+597D
```

这些字符超过了 `U+00FF`，`LATIN1` 存不下，所以必须用 `UTF16`：

```text
value = [4F 60, 59 7D]
coder = UTF16
```

上面的 `UTF16` 字节只是概念表示，真实字节顺序是 JDK 内部实现细节。

可以用下标关系理解两者的差异：

```text
LATIN1:
第 i 个 char 在 value[i]

UTF16:
第 i 个 char 在 value[i * 2] 和 value[i * 2 + 1]
```

所以对 `StringBuilder` 来说：

```java
new StringBuilder("abc")
```

内部大概是：

```text
coder = LATIN1
capacity = 19
value.length = 19
```

而：

```java
new StringBuilder("你好")
```

内部大概是：

```text
coder = UTF16
capacity = 18
value.length = 36
```

这里 `capacity` 指的是能容纳多少个 Java `char`，不是底层 `byte[]` 的真实长度。`UTF16` 模式下，底层 `byte[]` 长度通常是 `capacity * 2`。

---

## 4. 为什么说对外仍然是同一种 `String`

`LATIN1` 和 `UTF16` 是 JDK 的内部存储策略，不是 Java 对外暴露的两种字符串类型。

不是这样：

```java
Latin1String s1 = ...;
Utf16String s2 = ...;
```

而是始终这样：

```java
String s1 = "abc";
String s2 = "你好";
```

对使用者来说，它们都是 `java.lang.String`：

```java
s1.length();
s1.charAt(0);
s1.substring(1);
s1.equals("abc");
```

这些 API 不会因为内部 `coder` 不同而变成两套行为。

例如：

```java
String s = "abc";
char c = s.charAt(0);
```

虽然 `"abc"` 内部可能是 `LATIN1`，也就是一个字符只用 1 字节存，但 `charAt(0)` 返回的仍然是 Java 的 `char`：

```java
'a'
```

JDK 会在内部把那个 1 字节还原成 Java `char` 给调用者。

---

## 5. `LATIN1` / `UTF16` 和 Unicode 的关系

可以这样区分三者：

```text
Unicode：给字符编号
LATIN1 / UTF16：把这些编号存成字节的方式
```

Unicode 规定字符和编号之间的关系，例如：

```text
A  = U+0041
é  = U+00E9
你 = U+4F60
😀 = U+1F600
```

这些 `U+0041`、`U+4F60` 叫 Unicode 码点。

### 5.1 LATIN1

`LATIN1` 只能表示 Unicode 里的前 256 个码点：

```text
U+0000 到 U+00FF
```

它们可以直接用 1 个字节存。

例如：

```text
A = U+0041 -> 0x41
é = U+00E9 -> 0xE9
```

但是：

```text
你 = U+4F60
```

超过了 `U+00FF`，所以 `LATIN1` 存不了。

### 5.2 UTF16

`UTF16` 是 Unicode 的一种编码方式，可以表示完整 Unicode 字符集。

例如：

```text
A  = U+0041  -> 0x0041
你 = U+4F60  -> 0x4F60
```

对于补充字符，例如：

```text
😀 = U+1F600
```

UTF-16 会用两个 Java `char`，也就是一组代理对：

```text
0xD83D 0xDE00
```

所以三者关系可以总结成：

```text
Unicode 是字符编号标准。

LATIN1 是一种 1 字节存储方式，
只能存 Unicode 的 U+0000 到 U+00FF。

UTF16 是一种 2 字节或 4 字节存储方式，
可以存完整 Unicode。
```

---

## 6. `StringBuilder` 从 LATIN1 变成 UTF16

如果一个 `StringBuilder` 一开始只包含英文，它可以用 `LATIN1`：

```java
StringBuilder sb = new StringBuilder("abc");
```

内部大概是：

```text
value = [0x61, 0x62, 0x63, ...]
coder = LATIN1
```

如果后来追加中文：

```java
sb.append("你好");
```

原来的 `LATIN1` 已经装不下新内容，JDK 会把内部存储扩展成 `UTF16`：

```text
value = [0x00 0x61, 0x00 0x62, 0x00 0x63, 0x4F 0x60, 0x59 0x7D, ...]
coder = UTF16
```

这一步可以理解成：

1. 发现新增字符不能用 `LATIN1` 表示
2. 分配或调整为 `UTF16` 格式的 `byte[]`
3. 把已有的英文字符从 1 字节形式扩展成 2 字节形式
4. 再继续追加中文

### 6.1 转换时是否要处理每个字符

需要。

原因是不能只把 `coder` 从 `LATIN1` 改成 `UTF16`。如果只改标记，原来的 `byte[]` 会被错误解释。

原来是：

```text
LATIN1:
value = [61, 62, 63]
```

如果直接把 `coder` 改成 `UTF16`，JDK 会按两个字节一组读：

```text
[61, 62] [63, ?]
```

内容就坏了。

所以必须把已有内容重新整理成 UTF-16 形式：

```text
UTF16:
value = [00, 61, 00, 62, 00, 63]
```

也就是说，已有的每个字符都要被扩宽：

```text
0x61 -> 0x0061
0x62 -> 0x0062
0x63 -> 0x0063
```

所以一次升级的成本可以理解成：

```text
转换已有内容：O(当前长度)
追加新内容：O(追加内容长度)
```

真实 JDK 实现可能使用更底层的数组复制和优化循环，但从语义上看，已有内容中的每个字符都必须完成从 1 字节到 2 字节的表示转换。

### 6.2 升级后会不会再降回 LATIN1

通常不会。

例如：

```java
StringBuilder sb = new StringBuilder("abc"); // LATIN1
sb.append("你");                              // 升级成 UTF16
sb.append("def");                             // 仍然是 UTF16
```

一旦 `StringBuilder` 升级成 `UTF16`，后面即使只追加英文，也会继续按 `UTF16` 写入，不会因为新增内容都能用 `LATIN1` 表示就自动降回去。

所以可以把 `StringBuilder` 的编码变化理解成单向升级：

```text
LATIN1 -> UTF16
```

而不是：

```text
LATIN1 <-> UTF16
```

---

## 7. 最后总结

可以把这几个概念按层次记住：

```text
对外语义层：
String 是 Unicode 文本

内部存储层：
JDK 9+ 使用 byte[] value + byte coder

coder = LATIN1：
每个 Java char 用 1 个字节存，只能表示 U+0000 到 U+00FF

coder = UTF16：
每个 Java char 用 2 个字节存，可以表示完整 Unicode
```

所以 `coder` 不是新的字符串类型，也不是业务代码应该依赖的概念。它只是 JDK 为了减少字符串内存占用而引入的内部实现细节。
