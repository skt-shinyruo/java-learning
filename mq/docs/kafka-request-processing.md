# Kafka Broker 请求处理链路：Acceptor、Processor、RequestChannel 与 KafkaApis

这篇笔记说明 Kafka Broker 处理客户端请求时的经典数据面路径。重点是区分网络线程、请求处理线程，以及请求队列和响应队列；这些概念经常被简化成一条“请求/响应共用队列”，但 Kafka 的实际实现并不是这样。

## 1. 结论

下面的流程图作为 Broker 数据面请求的高层模型是正确的：

```text
客户端 -> Acceptor -> Processor -> 请求处理线程 -> API 分发 -> Processor -> 客户端
```

需要做三点修正：

1. `KafkaRequestHandler` 调用的入口是 `KafkaApis.handle()`，再由它按照 `ApiKey` 分发到 `handleProduceRequest()`、`handleFetchRequest()` 等方法。
2. `RequestChannel` 的请求队列和响应队列不是同一条队列。请求进入共享的有界 `requestQueue`；响应则按照原始 `processorId` 路由到对应 Processor 的响应队列。
3. 响应可能在当前处理线程中同步生成，也可能由延迟操作或异步回调稍后生成，因此不应假设响应一定紧跟在 `handle()` 返回前产生。

## 2. 修正后的流程图

```mermaid
flowchart LR
    A[客户端] -->|TCP 建连| B["Acceptor<br/>(每个监听端点 1 个)"]
    B -->|分配 SocketChannel| C["Processor<br/>(每个监听器 N 个, NIO Selector)"]

    C -->|解析、校验并提交请求| D["RequestChannel.requestQueue<br/>(共享有界队列)"]
    D -->|poll / take| E["KafkaRequestHandler<br/>(线程池 M)"]
    E -->|调用| F["KafkaApis.handle()"]
    F -->|按 ApiKey 分发| G["handleProduceRequest()<br/>handleFetchRequest()<br/>其他 API"]

    G -->|同步或异步完成后<br/>RequestChannel.sendResponse()| H["原 Processor.responseQueue<br/>(每个 Processor 独立)"]
    H -->|processNewResponses()| C
    C -->|Selector 写入 SocketChannel| A
```

图中 `RequestChannel` 被拆成了两个逻辑部分：共享的请求队列，以及由各个 Processor 持有的响应队列。`RequestChannel.sendResponse()` 负责根据请求保存的 `processorId` 找到目标 Processor 并投递响应。

## 3. 各组件职责

### 3.1 Acceptor：接受连接并分配 Processor

Kafka 为每个监听端点创建一个 Acceptor 线程。它负责：

- 监听服务端 Socket；
- 接受客户端的 TCP 连接；
- 设置新连接的 Socket 参数；
- 将 `SocketChannel` 分配给 Processor。

多个连接通常在 Processor 之间轮询分配。Acceptor 不负责读取完整请求，也不负责执行业务 API。

这里的“每个监听器一个”更准确地说是“每个 endpoint 一个”。在常见配置中，一个 endpoint 对应一个 listener；如果讨论 KRaft 的 controller listener，则应把它和 Broker 的数据面 listener 分开考虑。

### 3.2 Processor：NIO 网络事件循环

Processor 是网络线程，每个 Processor 持有一个 NIO `Selector`，负责多个连接上的网络事件：

- 注册新连接；
- 读取请求字节并解析请求头；
- 执行连接级的认证、版本和协议检查；
- 将完成解析的请求提交给 `RequestChannel`；
- 取出属于自己的响应并交给 Selector 写回客户端。

`N` 通常由 `num.network.threads` 决定。Kafka 当前配置文档说明，数据面 listener 会创建自己的网络线程池；controller listener 是一个需要单独说明的例外。

Processor 的主循环会反复处理新连接、新响应、Selector 事件、完成的接收和发送：

```text
configureNewConnections()
processNewResponses()
poll()
processCompletedReceives()
processCompletedSends()
```

因此“Processor 轮询响应并写入通道”这个方向是对的，但响应实际先进入该 Processor 自己的响应队列，再由网络事件循环交给 Selector。

### 3.3 RequestChannel：请求和响应的队列边界

#### 请求侧

请求侧是一个共享的有界队列：

```scala
requestQueue = new ArrayBlockingQueue[BaseRequest](queueSize)
```

Processor 通过 `sendRequest()` 放入请求，KafkaRequestHandler 通过 `receiveRequest()` 取出请求。`queueSize` 对应 `queued.max.requests`。队列满时，网络线程提交请求会受到阻塞，从而形成背压。

#### 响应侧

响应不是重新放回上面的 `requestQueue`。`sendResponse()` 会读取请求中的 Processor 标识，将响应投递到目标 Processor 的响应队列：

```scala
processor.enqueueResponse(response)
```

当前实现中，Processor 的响应队列是：

```scala
responseQueue = new LinkedBlockingDeque[Response]()
```

所以原图中的“响应入队 -> RequestChannel -> 轮询响应”可以作为概念上的控制流，但如果要表达数据结构，就应明确画出 Processor 级别的响应队列。RequestChannel 还包含用于异步回调和唤醒的队列，这些也不应与主请求队列混为一谈。

### 3.4 KafkaRequestHandler：执行请求处理

KafkaRequestHandler 线程从 `RequestChannel` 取请求，然后调用：

```scala
apis.handle(request, requestLocal)
```

Broker 数据面通常传入的是 `KafkaApis`。`KafkaApis.handle()` 再按照请求头中的 `ApiKey` 分发到具体处理方法，例如：

```scala
case ApiKeys.PRODUCE => handleProduceRequest(request, requestLocal)
case ApiKeys.FETCH   => handleFetchRequest(request)
```

`M` 通常对应 `num.io.threads`，它表示处理请求的 I/O 线程数，处理过程可能包含磁盘 I/O。

### 3.5 响应生成不一定是同步的

某些 API 可以在当前 Handler 线程中直接构造响应；另一些请求会进入副本管理、协调器或延迟操作流程，等条件满足后由回调完成响应。典型路径是：

```text
KafkaApis.handle()
    -> 发起内部操作
    -> 稍后执行 response callback
    -> RequestChannel.sendResponse()
```

此外，`acks=0` 的 Produce 请求可能只需要 `NoOpResponse` 或关闭连接，并不一定向客户端发送一个普通的业务响应。因此图中的响应路径应理解为“有响应时的典型路径”。

## 4. 参数与实现的对应关系

| 图中概念 | Kafka 配置或实现 | 说明 |
| --- | --- | --- |
| `N` | `num.network.threads` | 每个数据面监听器的网络 Processor 数量 |
| `M` | `num.io.threads` | Broker 请求处理线程池大小 |
| 请求队列容量 | `queued.max.requests` | 共享 `requestQueue` 的容量；满时对网络线程形成背压 |
| 请求入口 | `RequestChannel.sendRequest()` | Processor 提交已解析的请求 |
| 请求出口 | `RequestChannel.receiveRequest()` | Handler 线程取出请求 |
| 响应入口 | `RequestChannel.sendResponse()` | 按 `processorId` 路由到目标 Processor |
| 响应出口 | `Processor.processNewResponses()` | Processor 将响应交给 Selector 写回网络 |

## 5. 适用范围和版本注意事项

本文的类名和调用关系对应 Apache Kafka 当前服务端源码中的经典 Broker 数据面路径，适合作为理解 Kafka 网络层和请求处理层的基础模型。不同 Kafka 版本可能调整类的语言、线程池配置或 KRaft 内部实现，但以下边界通常仍然成立：

- 网络线程负责连接和 I/O，不负责执行主要业务处理；
- 请求处理线程从请求队列取任务；
- 响应必须回到产生请求的 Processor；
- Controller listener 的请求处理路径不能简单等同于 Broker 的 `KafkaApis` 路径。

## 6. 官方源码与配置

- [SocketServer.scala](https://github.com/apache/kafka/blob/trunk/core/src/main/scala/kafka/network/SocketServer.scala)：Acceptor、Processor、Selector 事件循环和响应投递。
- [RequestChannel.scala](https://github.com/apache/kafka/blob/trunk/core/src/main/scala/kafka/network/RequestChannel.scala)：请求队列、响应路由和 Processor 注册。
- [KafkaRequestHandler.scala](https://github.com/apache/kafka/blob/trunk/core/src/main/scala/kafka/server/KafkaRequestHandler.scala)：请求处理线程和线程池。
- [KafkaApis.scala](https://github.com/apache/kafka/blob/trunk/core/src/main/scala/kafka/server/KafkaApis.scala)：按 `ApiKey` 分发请求。
- [Kafka Broker 配置](https://kafka.apache.org/40/generated/kafka_config.html)：`num.network.threads`、`num.io.threads` 和 `queued.max.requests`。
