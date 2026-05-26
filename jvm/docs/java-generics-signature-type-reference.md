# Java 泛型、Signature 与 TypeReference

Java 泛型最容易混淆的地方在于：源码里有 `List<String>`，运行时对象却通常只知道自己是 `List` 或 `ArrayList`。要把这个问题讲清楚，需要同时看三层信息：

- 源码层：`List<String>`、`T`、`? extends Number` 这些泛型写法。
- 字节码执行层：字段描述符、方法描述符和 `Code` 属性里使用的是擦除后的类型。
- Class 文件元数据层：`Signature`、`LocalVariableTypeTable` 等属性可以额外保存泛型签名，反射 API 会从这里恢复一部分泛型信息。

可以先记住一句话：

- 泛型在 JVM 执行层被擦除，但一部分泛型签名会作为元数据留在 Class 文件里。

---

## 1. 类型擦除到底擦掉了什么

例如：

```java
List<String> names = new ArrayList<String>();
names.add("Tom");
String first = names.get(0);
```

编译后，`ArrayList<String>` 不会变成一个新的运行时类型。JVM 看到的仍然是普通的 `ArrayList`，`List.get` 的方法描述符也仍然返回 `Object`：

```text
invokeinterface java/util/List.get:(I)Ljava/lang/Object;
checkcast java/lang/String
```

也就是说，编译器在源码层检查 `names` 只能放 `String`，但字节码调用 `List.get` 时返回的还是 `Object`。当结果要赋给 `String` 变量时，编译器补上一条 `checkcast java/lang/String`。

这就是类型擦除的基本形态：

```text
源码类型                  执行层类型
List<String>             List
Map<String, Integer>     Map
T                        T 的上界，默认 Object
T extends Number         Number
```

所以运行时不能把 `List<String>` 和 `List<Integer>` 当成两个不同的类。它们擦除后都是 `List`。

---

## 2. descriptor 与 Signature 的分工

Class 文件里有两套容易混淆的信息：

```text
descriptor  JVM 执行、校验、方法分派使用的类型描述，泛型已擦除
Signature   额外保存的泛型签名，供编译器、反射和工具读取
```

看一个例子：

```java
class GenericDemo<T extends Number> {
    private List<String> names;
    private T value;

    T first(List<T> list) {
        return list.get(0);
    }
}
```

用 `javap -v -p GenericDemo.class` 观察，字段大致会出现类似结构：

```text
private java.util.List names;
  descriptor: Ljava/util/List;
  Signature: Ljava/util/List<Ljava/lang/String;>;

private T value;
  descriptor: Ljava/lang/Number;
  Signature: TT;
```

`descriptor` 是擦除后的类型。`List<String>` 的字段描述符只是 `Ljava/util/List;`；`T extends Number` 的字段描述符是 `Ljava/lang/Number;`。

`Signature` 才保留源码里的泛型形态：

```text
Ljava/util/List<Ljava/lang/String;>;   // List<String>
TT;                                    // 类型变量 T
```

方法也类似：

```text
T first(java.util.List<T>);
  descriptor: (Ljava/util/List;)Ljava/lang/Number;
  Signature: (Ljava/util/List<TT;>;)TT;
```

方法的 `Code` 属性里仍然调用擦除后的 `List.get`：

```text
0: aload_1
1: iconst_0
2: invokeinterface java/util/List.get:(I)Ljava/lang/Object;
7: checkcast java/lang/Number
10: areturn
```

所以“擦除”主要发生在执行语义上：JVM 校验和执行字节码时依赖 descriptor 和 `Code`，不会把 `List<String>` 当成真实运行时类型。`Signature` 是额外元数据，让反射和工具还能看到一部分泛型签名。

---

## 3. 反射 Type 体系在表达什么

`Class<?>` 只能表达确定的运行时类，例如：

```java
String.class
Integer.class
List.class
String[].class
```

但 `List<String>`、`T`、`? extends Number` 这些不是单纯的 `Class<?>` 能表达的。Java 反射用 `java.lang.reflect.Type` 及其几种实现来描述这些签名：

| 类型 | 表达内容 | 例子 |
| --- | --- | --- |
| `Class<?>` | 确定的普通类型或数组类型 | `String`、`List`、`String[]` |
| `ParameterizedType` | 带类型参数的类型 | `List<String>`、`Map<String, Integer>` |
| `TypeVariable` | 类型变量 | `T`、`E`、`K`、`V` |
| `WildcardType` | 通配符 | `?`、`? extends Number`、`? super Integer` |
| `GenericArrayType` | 组件类型含泛型信息的数组 | `T[]`、`List<String>[]` |

这些对象通常来自 `Signature` 等元数据。例如：

```java
class TypeDemo<T extends Number> {
    String name;
    List<String> names;
    T value;
    List<? extends Number> numbers;
    T[] values;
}
```

对应关系可以这样理解：

```text
String name                  -> Class<String>
List<String> names           -> ParameterizedType
T value                      -> TypeVariable
List<? extends Number>       -> ParameterizedType，内部含 WildcardType
T[] values                   -> GenericArrayType
```

需要注意：这些 `Type` 对象描述的是类、字段、方法签名里的类型信息，不代表每个对象实例都携带了自己的泛型实参。一个普通的 `new ArrayList<String>()` 对象运行时仍然只是 `ArrayList`。

---

## 4. TypeReference 为什么能拿到 List<String>

Jackson 的 `TypeReference`、Guava 的 `TypeToken` 使用的是同一个核心技巧：把泛型实参写进匿名子类的父类签名里。

典型用法是：

```java
TypeReference<List<String>> ref = new TypeReference<List<String>>() {};
```

最后的 `{}` 很关键。它创建了一个匿名子类，近似等价于：

```java
class Demo$1 extends TypeReference<List<String>> {
}
```

这个匿名子类的普通父类仍然只是 `TypeReference`，但它的 Class 文件会带有泛型父类签名：

```text
super_class: TypeReference
Signature: LTypeReference<Ljava/util/List<Ljava/lang/String;>;>;
```

`TypeReference` 的构造方法可以读取这个签名：

```java
public abstract class TypeReference<T> {
    private final Type type;

    protected TypeReference() {
        Type superClass = getClass().getGenericSuperclass();
        if (!(superClass instanceof ParameterizedType)) {
            throw new IllegalArgumentException("Missing type parameter");
        }
        this.type = ((ParameterizedType) superClass).getActualTypeArguments()[0];
    }

    public Type getType() {
        return type;
    }
}
```

当执行：

```java
new TypeReference<List<String>>() {}
```

`getClass()` 拿到的是匿名子类 `Demo$1.class`。这个类的 `Signature` 里明确写着：

```text
TypeReference<List<String>>
```

于是 `getGenericSuperclass()` 可以解析出 `ParameterizedType`，再拿到实际类型参数 `List<String>`。

这不是 JVM 真正具现化了 `List<String>`，而是利用了 Class 文件元数据中保留的泛型父类签名。

---

## 5. TypeReference 的边界

`TypeReference` 能捕获的是“写在匿名子类签名里的类型”，不能恢复已经被擦除的运行时类型。

这个写法可以捕获完整类型：

```java
TypeReference<List<String>> ref = new TypeReference<List<String>>() {};
```

因为 `List<String>` 明确写在匿名子类的父类签名里。

但下面这个写法不能得到调用方的具体实参：

```java
static <T> TypeReference<List<T>> make() {
    return new TypeReference<List<T>>() {};
}
```

匿名子类的签名只能保存：

```text
LTypeReference<Ljava/util/List<TT;>;>;
```

也就是 `List<T>`，不是 `List<String>` 或 `List<Integer>`。因为 `T` 本身只是当前方法的类型变量，方法调用时不会把具体类型实参写进这个匿名类的 Class 文件。

所以 `TypeReference` 适合解决的问题是：

```text
代码里明确写了 List<User>，运行时想把这个签名交给框架读取。
```

它解决不了的问题是：

```text
手上只有一个 List 对象，运行时想判断它原本是不是 List<User>。
```

后者通常做不到，因为对象实例没有携带 `User` 这个泛型实参。

---

## 6. 为什么不能 new T[]

数组和泛型的冲突也来自同一个根源。

数组是运行期保留组件类型的。例如：

```java
String[] names = new String[10];
```

字节码创建数组时需要明确的组件类型：

```text
anewarray java/lang/String
```

但泛型类型变量 `T` 在执行层会被擦除。如果写：

```java
T[] values = new T[10];
```

编译器没有办法生成真正的 `anewarray T`。如果把它偷偷改成 `new Object[10]`，当调用方期待 `String[]` 时就会出错；如果 `T extends Number`，偷偷生成 `Number[]` 也不能等价于 `Integer[]`。

因此 Java 禁止直接创建泛型数组。常见解决办法是把运行期组件类型显式传进来：

```java
@SuppressWarnings("unchecked")
static <T> T[] convert(List<T> list, Class<T> componentType) {
    T[] array = (T[]) Array.newInstance(componentType, list.size());
    return list.toArray(array);
}
```

或者让调用方传入数组样板：

```java
static <T> T[] convert(List<T> list, T[] array) {
    return list.toArray(array);
}
```

这两种方式的本质都是：泛型 `T` 本身不能提供运行期组件类型，所以必须从额外参数里提供 `String.class`、`new String[0]` 这类可具现化的信息。

---

## 7. 小结

可以按下面的路径理解 Java 泛型与字节码的关系：

```text
源码
  List<String>、T、? extends Number

编译
  泛型类型参与类型检查
  访问泛型元素时按需插入 checkcast
  泛型签名写入 Signature 等属性

执行
  JVM 使用 descriptor 和 Code
  List<String> 作为运行时类型被擦除成 List
  T 被擦除成上界，默认 Object

反射
  Field.getType() / Method.getReturnType() 看到擦除后的 Class
  Field.getGenericType() / Method.getGenericReturnType() / Class.getGenericSuperclass() 可读取 Signature
  ParameterizedType / TypeVariable / WildcardType / GenericArrayType 用来描述泛型签名

TypeReference / TypeToken
  通过匿名子类把 List<String> 写进父类 Signature
  运行时通过反射读取这个 Signature
  只能捕获写在签名里的类型，不能恢复对象实例已经丢失的泛型实参
```

相关的 Class 文件属性结构可以继续看 [Class 文件结构](class-file-structure.md) 中关于 `Signature`、字段表、方法表和 `Code` 属性的说明。
