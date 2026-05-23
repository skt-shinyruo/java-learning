# JVM 方法调用与返回指令

这篇文档简单整理 JVM 里几类常见的方法调用指令，以及方法执行结束时对应的返回指令。

先记住两个核心点：

- 方法调用指令关心的是“怎么找到并进入目标方法”。
- 方法返回指令关心的是“当前方法结束时，从操作数栈带回什么类型的值”。

方法调用指令和参数、返回值的数据类型没有直接对应关系；返回指令才按返回值类型区分。

---

## 1. 五条方法调用指令

JVM 为不同的方法调用场景设计了不同的字节码指令：

| 指令 | 主要用途 | 目标确定方式 |
| --- | --- | --- |
| `invokevirtual` | 调用普通实例方法 | 根据接收者对象的实际类型做虚方法分派 |
| `invokeinterface` | 调用接口方法 | 运行时在实现类中查找合适的方法 |
| `invokespecial` | 调用构造器、私有方法、父类方法 | 不按普通虚方法重写规则分派 |
| `invokestatic` | 调用静态方法 | 目标属于类本身，不需要接收者对象 |
| `invokedynamic` | 动态调用点，例如 lambda、动态语言调用 | 由 bootstrap method 在运行时链接出调用目标 |

前四条指令的链接和分派规则主要固化在 JVM 内部。`invokedynamic` 比较特殊，它把“这个调用点应该绑定到哪个目标”的决定权交给引导方法，JVM 负责调用引导方法并缓存链接结果。

---

## 2. `invokevirtual`

`invokevirtual` 用于调用普通实例方法，也是 Java 里最常见的方法调用形式：

```java
class Animal {
    void speak() {
        System.out.println("animal");
    }
}

class Dog extends Animal {
    @Override
    void speak() {
        System.out.println("dog");
    }
}

Animal animal = new Dog();
animal.speak();
```

源码变量类型是 `Animal`，但运行时对象实际是 `Dog`。JVM 执行 `invokevirtual` 时会根据接收者对象的实际类型选择 `Dog.speak()`。这就是虚方法分派。

可以把它理解成：

```text
操作数栈中有接收者对象和参数
  -> 根据对象实际类型查找目标方法
  -> 创建被调用方法的新栈帧
  -> 跳入目标方法执行
```

### 2.1 为什么调用指令的栈效应是固定的

`invokevirtual` 本身只负责“调用哪个实例方法”，但这条字节码要弹出多少操作数、是否压回返回值，取决于方法描述符。

方法描述符的格式是：

```text
(参数类型...)返回类型
```

例如：

- `println:(Ljava/lang/String;)V` 表示接收者对象加 1 个 `String` 参数，返回 `void`
- `substring:(I)Ljava/lang/String;` 表示接收者对象加 1 个 `int` 参数，返回 1 个引用

所以对同一个调用点来说，运行时即使根据接收者对象的实际类型选择了不同的重写实现，栈的进出规则也不会变。会变的是“执行哪段方法体”，不会变的是“这条调用指令要怎么改操作数栈”。

这也是编译器和字节码验证器能够静态计算 `max_stack` 的原因之一。

---

## 3. `invokeinterface`

`invokeinterface` 用于调用接口方法：

```java
List<String> list = new ArrayList<>();
list.add("java");
```

源码里看到的是 `List.add`，但运行时接收者对象可能是 `ArrayList`、`LinkedList`，也可能是其他实现类。JVM 会在对象的实际类型中查找这个接口方法的实现。

它和 `invokevirtual` 都需要运行时分派。区别在于，接口方法调用的符号引用来自接口方法引用，查找规则要处理“接口到实现类”的关系。

---

## 4. `invokespecial`

`invokespecial` 用于一些不能按普通虚方法分派处理的实例调用，常见场景包括：

- 实例构造器 `<init>`
- 私有方法
- `super.xxx()` 调用父类方法

例如：

```java
class Parent {
    void hello() {
        System.out.println("parent");
    }
}

class Child extends Parent {
    Child() {
        super();
    }

    @Override
    void hello() {
        super.hello();
    }
}
```

`super()` 和 `super.hello()` 都不是“根据当前对象实际类型继续向下找重写版本”。它们要调用的是一个被明确指定的特殊目标，所以使用 `invokespecial`。

构造器调用也用 `invokespecial`。对象创建通常会出现这样的字节码形态：

```text
new
dup
invokespecial <init>
```

`new` 分配对象，`dup` 保留一份对象引用，`invokespecial <init>` 调用构造器完成初始化。

---

## 5. `invokestatic`

`invokestatic` 用于调用静态方法：

```java
int max = Math.max(a, b);
```

静态方法属于类，不属于某个对象实例，所以调用时不需要从操作数栈弹出 `this` 引用。它的目标通常可以在解析阶段确定，比普通虚方法调用更直接。

需要注意的是，静态方法可以被子类声明同名方法“隐藏”，但这不是实例方法重写。静态方法调用目标由编译期类型决定，不由运行时对象实际类型决定。

---

## 6. `invokedynamic`

`invokedynamic` 用于动态调用点。它不是简单地在常量池里写死一个普通目标方法，而是通过 `BootstrapMethods` 属性找到引导方法，由引导方法返回一个 `CallSite`。

第一次执行某个 `invokedynamic` 调用点时，大致流程是：

```text
invokedynamic
  -> 找到 CONSTANT_InvokeDynamic
  -> 找到 BootstrapMethods 中的引导方法
  -> 调用引导方法
  -> 得到 CallSite
  -> 绑定并缓存调用点
  -> 调用 CallSite.target
```

Java 8 lambda 是常见例子：

```java
Runnable task = () -> System.out.println("run");
```

编译后通常会生成一个保存 lambda body 的辅助方法，再用 `invokedynamic` 链接出函数式接口实例。更完整的运行时结构可以继续看 [MethodHandle 与 invokedynamic](method-handle-invokedynamic.md)。

---

## 7. 方法返回指令

方法返回指令按返回值类型区分：

| 指令 | 返回值类型 |
| --- | --- |
| `ireturn` | `boolean`、`byte`、`char`、`short`、`int` |
| `lreturn` | `long` |
| `freturn` | `float` |
| `dreturn` | `double` |
| `areturn` | 引用类型，包括对象和数组 |
| `return` | `void` 方法、构造器、类或接口初始化方法 |

例如：

```java
int size() {
    return 1;
}

String name() {
    return "java";
}

void close() {
}
```

对应的返回指令分别会接近：

```text
size()  -> ireturn
name()  -> areturn
close() -> return
```

返回指令会结束当前方法的栈帧。如果有返回值，JVM 会从当前方法的操作数栈顶取出对应类型的值，交给调用者栈帧继续使用。

---

## 8. 调用指令和返回指令的关系

调用指令不根据返回类型命名。一个 `invokevirtual` 可以调用返回 `int` 的方法，也可以调用返回对象的方法：

```java
String text = "abc";
int len = text.length();       // invokevirtual，目标方法内部用 ireturn
String sub = text.substring(1); // invokevirtual，目标方法内部用 areturn
```

也就是说：

```text
调用方：
  invokevirtual / invokeinterface / invokespecial / invokestatic / invokedynamic
  负责进入目标方法

被调用方：
  ireturn / lreturn / freturn / dreturn / areturn / return
  负责结束当前方法并把结果交还给调用方
```

如果只看 `javap` 输出，可以按这个顺序读：

1. 先看调用点使用的是哪条 `invoke*` 指令，判断调用场景。
2. 再看目标方法的描述符，例如 `()I`、`()Ljava/lang/String;`、`()V`。
3. 最后看目标方法自己的字节码，用返回指令确认它怎么结束。

这样就能把“方法怎么被调用”和“方法怎么返回”分开理解。
