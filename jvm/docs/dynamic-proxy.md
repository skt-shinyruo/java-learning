# JDK 动态代理与 InvocationHandler

JDK 动态代理的核心不是把所有业务逻辑都生成到代理类里，而是运行时生成一个实现目标接口的转发类。这个转发类继承 `java.lang.reflect.Proxy`，并把接口方法调用统一交给创建代理对象时传入的 `InvocationHandler`。

先记住一句话：

```text
$ProxyN 负责接住接口调用；InvocationHandler.invoke(...) 负责真正的代理逻辑。
```

---

## 1. 一个最小例子

假设原始对象只负责打印业务内容：

```java
interface IHello {
    void sayHello();
}

class Hello implements IHello {
    @Override
    public void sayHello() {
        System.out.println("hello world");
    }
}
```

代理逻辑写在 `InvocationHandler` 里：

```java
class WelcomeHandler implements InvocationHandler {
    private final Object target;

    WelcomeHandler(Object target) {
        this.target = target;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        System.out.println("welcome");
        return method.invoke(target, args);
    }
}
```

创建代理对象时，把这个 handler 传给 `Proxy.newProxyInstance(...)`：

```java
IHello hello = (IHello) Proxy.newProxyInstance(
        Hello.class.getClassLoader(),
        new Class<?>[] { IHello.class },
        new WelcomeHandler(new Hello()));

hello.sayHello();
```

输出顺序是：

```text
welcome
hello world
```

这里的 `welcome` 并不是代理类自己生成出来的固定逻辑，而是 `WelcomeHandler.invoke(...)` 里的用户代码。

---

## 2. 生成的代理类大致长什么样

JDK 会为接口生成类似 `$Proxy0` 的类。反编译后可以把关键结构简化成这样：

```java
public final class $Proxy0 extends Proxy implements IHello {
    private static Method mSayHello;

    public $Proxy0(InvocationHandler h) {
        super(h);
    }

    @Override
    public final void sayHello() {
        try {
            this.h.invoke(this, mSayHello, null);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new UndeclaredThrowableException(t);
        }
    }
}
```

最关键的是这一行：

```java
this.h.invoke(this, mSayHello, null);
```

`h` 是父类 `Proxy` 保存的 `InvocationHandler` 字段。构造代理对象时，`Proxy.newProxyInstance(...)` 会把用户传入的 handler 放进去。之后每次调用代理对象的接口方法，生成的 `$Proxy0` 方法体都会转调 `h.invoke(...)`。

---

## 3. `welcome` 这类增强逻辑在哪里执行

调用链可以简化为：

```text
hello.sayHello()
  -> $Proxy0.sayHello()
  -> this.h.invoke(this, mSayHello, null)
  -> WelcomeHandler.invoke(...)
  -> System.out.println("welcome")
  -> method.invoke(target, args)
  -> Hello.sayHello()
  -> System.out.println("hello world")
```

所以要区分三段逻辑：

| 位置 | 职责 |
| --- | --- |
| `$Proxy0.sayHello()` | JDK 生成的转发壳，负责把接口调用交给 `InvocationHandler` |
| `InvocationHandler.invoke(...)` | 用户写的代理增强逻辑，例如打印 `welcome`、鉴权、事务、日志等 |
| `method.invoke(target, args)` | 通过反射调用原始目标对象的方法 |

如果反编译 `$Proxy0` 时没有看到 `System.out.println("welcome")`，这是正常的。生成类只需要知道“把 `sayHello()` 转发给哪个 handler”，不需要也不会把 handler 里的 Java 源码复制进 `$Proxy0`。

---

## 4. 为什么 `$Proxy0` 还保存 Method 字段

`InvocationHandler.invoke(...)` 的签名是：

```java
Object invoke(Object proxy, Method method, Object[] args) throws Throwable;
```

因此 `$Proxy0.sayHello()` 转发时需要告诉 handler：这次被调用的是哪个接口方法。生成类通常会在静态初始化阶段缓存接口方法对应的 `Method` 对象，然后在转发时传入：

```text
mSayHello -> IHello.sayHello()
```

这样同一个 `InvocationHandler` 可以根据 `method` 做统一分派：

```java
if ("sayHello".equals(method.getName())) {
    // 针对 sayHello 的增强
}
```

动态代理也会为 `equals()`、`hashCode()`、`toString()` 生成类似的转发方法。区别只是传给 `invoke(...)` 的 `Method` 对象和参数数组不同。

---

## 5. 和 `invokedynamic` 的区别

JDK 动态代理主要依赖运行时生成 class，再通过普通方法调用进入 `$Proxy0`，最后由 `$Proxy0` 调用 `InvocationHandler.invoke(...)`。它不是 `invokedynamic` 机制。

可以粗略区分为：

```text
JDK 动态代理
  -> ProxyGenerator 生成 $ProxyN class
  -> 接口方法转发到 InvocationHandler.invoke(...)

invokedynamic
  -> 调用点第一次执行时运行 bootstrap method
  -> bootstrap method 返回 CallSite
  -> 后续调用走 CallSite.target
```

`invokedynamic`、`MethodHandle` 和 `CallSite` 的关系见 [MethodHandle 与 invokedynamic](method-handle-invokedynamic.md)。
