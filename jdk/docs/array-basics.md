# Java 数组：是什么，以及和原始类型、对象的区别

这篇文档的目标不是把数组相关语法零散罗列一遍，而是先把 6 个最容易混在一起的问题讲清楚：

- Java 数组到底是什么
- 数组和原始类型有什么区别
- 数组和普通对象有什么区别
- 数组在内存里该怎么理解
- `int[]` 和 `Integer[]` 到底差在哪
- 数组和 `ArrayList` 该怎么选

如果你只想先记住一句话，可以先记这个版本：

- 数组是“按顺序存放一组同类型数据”的特殊对象；数组本身是引用类型，但数组元素既可以是原始值，也可以是对象引用

---

## 1. Java 数组到底是什么

数组可以先理解成：

- 一块按顺序存放数据的结构
- 这组数据的元素类型必须一致
- 通过下标访问元素
- 下标从 `0` 开始
- 长度一旦创建就固定

例如：

```java
int[] nums = {10, 20, 30};
String[] names = {"Tom", "Jerry"};
```

这里：

- `nums` 是 `int` 数组
- `names` 是 `String` 数组

数组最常见的两个操作是访问元素和读取长度：

```java
System.out.println(nums[0]);   // 10
System.out.println(nums.length); // 3
```

先注意一个很容易被忽略的点：

- `int` 和 `int[]` 不是一回事
- `String` 和 `String[]` 也不是一回事

前者表示“一个值”或“一个对象引用”，后者表示“一组同类型元素”。

---

## 2. 数组和原始类型有什么区别

原始类型指的是：

- `int`
- `double`
- `char`
- `boolean`
- 以及其他基础数值类型

它们表示的是一个单独的值。

例如：

```java
int a = 5;
double price = 19.9;
boolean ready = true;
```

这里每个变量都只表示一个值。

数组则不同。数组不是单独的一个数或一个布尔值，而是一组值：

```java
int[] arr = {1, 2, 3};
```

可以直接这样区分：

- 原始类型：存一个值
- 数组：存多个同类型值

更重要的一点是：

- `int` 是原始类型
- `int[]` 不是原始类型
- `int[]` 是引用类型

这意味着：

- 原始类型变量里放的是那个值本身
- 数组变量里放的是“指向数组对象”的引用

所以 `int` 和 `int[]` 在语义层面完全不是同一种东西。

---

## 3. 数组和普通对象有什么区别

数组和普通对象有一个共同点：

- 它们都属于引用类型

例如：

```java
String s = "abc";
int[] arr = {1, 2, 3};
```

这里：

- `String` 是对象类型
- `int[]` 也是引用类型

它们都可以是 `null`：

```java
String s = null;
int[] arr = null;
```

但数组和普通对象仍然有明显区别。

### 3.1 数组是特殊对象

普通对象通常来自某个类，例如：

```java
class Person {
    String name;
    int age;
}
```

`Person` 对象描述的是一个实体，它可以有不同类型的字段：

- `name` 是 `String`
- `age` 是 `int`

数组则更单纯，它只负责按顺序放一组同类型元素。

例如：

```java
int[] scores = {90, 85, 100};
```

这里所有元素都必须是 `int`。

### 3.2 数组通过下标访问，对象通过字段访问

数组访问方式：

```java
arr[0]
arr[1]
```

对象访问方式：

```java
person.name
person.age
```

也就是说：

- 数组强调“位置”
- 对象强调“属性”

### 3.3 数组长度固定，对象结构固定

数组创建后，长度不能改：

```java
int[] arr = new int[3];
```

这个数组以后就只能容纳 3 个元素。

对象则不是按长度工作的，而是按字段结构来描述数据。例如 `Person` 始终有 `name` 和 `age` 这两个字段。

### 3.4 数组元素可能是原始值，也可能是对象引用

这是最容易混淆的一点。

例如：

```java
int[] a = {1, 2, 3};
String[] b = {"A", "B", "C"};
```

这里：

- `a` 这个数组的元素是原始类型 `int`
- `b` 这个数组的元素是 `String` 对象的引用

所以更准确的说法是：

- 数组本身总是引用类型
- 数组里的元素可以是原始类型，也可以是对象类型

---

## 4. 数组在内存里怎么理解

先看一个例子：

```java
int[] arr = {10, 20, 30};
```

可以先这样理解：

- `arr` 变量里存的不是三个数字本身
- `arr` 里存的是“数组对象的引用”
- 真正的数组内容在那块数组对象里

可以把它画成：

```text
arr  --->  [10, 20, 30]
```

也就是说：

- 数组变量保存的是引用
- 数组对象保存的是长度和元素内容

### 4.1 为什么两个数组变量可能互相影响

例如：

```java
int[] a = {1, 2, 3};
int[] b = a;
b[0] = 99;

System.out.println(a[0]); // 99
```

原因不是“数组会自动同步”，而是：

- `a` 和 `b` 指向同一个数组对象
- 改 `b[0]`，本质上是在改同一块数据

这和原始类型变量很不一样：

```java
int x = 5;
int y = x;
y = 9;

System.out.println(x); // 5
```

这里 `x` 和 `y` 是两个独立的值，后续互不影响。

### 4.2 数组创建后会有默认值

当你这样创建数组时：

```java
int[] nums = new int[3];
double[] ds = new double[2];
boolean[] bs = new boolean[2];
String[] ss = new String[2];
```

它们并不是“里面什么都没有”，而是会先被默认值填充：

- `int[]` 默认是 `0`
- `double[]` 默认是 `0.0`
- `boolean[]` 默认是 `false`
- 对象数组默认是 `null`

所以：

```java
System.out.println(nums[0]); // 0
System.out.println(ss[0]);   // null
```

可以这样记：

- 原始类型数组：用对应的零值填充
- 对象类型数组：用 `null` 填充

---

## 5. `int[]` 和 `Integer[]` 到底差在哪

先看定义：

```java
int[] a = {1, 2, 3};
Integer[] b = {1, 2, 3};
```

看起来都像“整数数组”，但它们并不是同一种东西。

### 5.1 元素类型不同

- `int[]` 的元素是原始类型 `int`
- `Integer[]` 的元素是对象类型 `Integer`

也就是说：

- `int[]` 里放的是整数值
- `Integer[]` 里放的是 `Integer` 对象引用

### 5.2 默认值不同

例如：

```java
int[] a = new int[3];
Integer[] b = new Integer[3];
```

结果分别可以理解成：

- `a` 是 `[0, 0, 0]`
- `b` 是 `[null, null, null]`

这是因为：

- `int` 不能是 `null`
- `Integer` 可以是 `null`

### 5.3 性能和空间开销不同

一般来说：

- `int[]` 更省内存
- `int[]` 访问更直接
- `Integer[]` 会有对象包装的额外开销

因为 `Integer` 不是一个简单数值，而是一个对象。

### 5.4 自动装箱和拆箱会带来便利，也会带来坑

例如：

```java
Integer x = 10;
int y = x;
```

这里分别发生了：

- 自动装箱：`int -> Integer`
- 自动拆箱：`Integer -> int`

这让代码看起来很自然，但有个常见风险：

```java
Integer x = null;
int y = x; // NullPointerException
```

因为拆箱时需要从 `Integer` 里取出 `int` 值，而 `null` 没法拆箱。

所以可以先记住一个很实用的经验：

- 只想高效存数字，优先用 `int[]`
- 需要和对象体系、集合体系配合，或者需要表示 `null`，再考虑 `Integer[]`

---

## 6. 数组和 `ArrayList` 该怎么选

数组和 `ArrayList` 都能存一组数据，但侧重点不同。

先看最基础的写法：

```java
int[] arr = new int[3];
```

```java
ArrayList<Integer> list = new ArrayList<>();
list.add(10);
list.add(20);
list.add(30);
```

### 6.1 长度是否固定

数组长度固定：

```java
int[] arr = new int[3];
```

创建时是 `3`，后面就不能直接变成 `4`。

`ArrayList` 大小可变：

```java
list.add(40);
list.remove(1);
```

它可以动态增删元素。

### 6.2 操作方式不同

数组主要靠下标：

```java
arr[0] = 10;
System.out.println(arr[0]);
```

`ArrayList` 主要靠方法：

```java
list.add(10);
list.get(0);
list.set(0, 99);
list.remove(0);
```

### 6.3 存储类型不同

数组可以直接存原始类型：

```java
int[] arr = {1, 2, 3};
```

但 `ArrayList` 的泛型参数不能写原始类型，只能写包装类型：

```java
ArrayList<Integer> list = new ArrayList<>();
```

下面这种写法是错误的：

```java
// ArrayList<int> list = new ArrayList<>();
```

### 6.4 常用长度接口不同

数组用 `length`：

```java
System.out.println(arr.length);
```

`ArrayList` 用 `size()`：

```java
System.out.println(list.size());
```

这也是初学时很容易写混的地方。

### 6.5 什么时候选数组，什么时候选 `ArrayList`

可以先按这个标准记：

- 数据量固定、结构简单、希望更直接一些：用数组
- 数据量经常变化、希望增删更方便：用 `ArrayList`

一般来说：

- 数组更轻量
- `ArrayList` 更灵活

开发里如果数据规模不固定，`ArrayList` 往往更常用；但在底层结构、固定长度数据、基础语法学习里，数组仍然非常重要。

---

## 7. 最后再总结一次

如果把全文压成 7 句话，可以这样记：

1. 数组是“按顺序存放一组同类型元素”的结构。
2. 数组下标从 `0` 开始，长度创建后固定。
3. 原始类型表示一个值，数组表示一组值。
4. 数组本身不是原始类型，而是引用类型。
5. 数组也是一种特殊对象，但它强调的是“同类型元素 + 顺序位置”。
6. `int[]` 存的是原始值，`Integer[]` 存的是对象引用，因此默认值、性能和 `null` 语义都不同。
7. 数组适合固定长度数据，`ArrayList` 更适合长度经常变化的数据。
