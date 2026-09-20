# live-chat：Netty WebSocket 在线客服 demo

一个单进程、零依赖安装的网页客服对话 demo：浏览器打开页面自动建立 WebSocket 连接，机器人客服自动应答；客户超过 3 分钟没有回复，服务端主动关闭会话并通知客户。

来源：[issue #3](https://github.com/skt-shinyruo/java-learning/issues/3)。学习目标不是构建生产级客服系统，而是两个点：

1. Netty 里 HTTP 与 WebSocket 在同一个端口、同一条 pipeline 上如何共存与分流
2. `HashedWheelTimer` 如何支撑海量连接的会话超时（每连接一个超时任务，而非每连接一个线程/调度线程）

## 快速开始

```bash
mvn -pl playground/live-chat exec:java -Dexec.mainClass=yier.bubu.playground.livechat.ChatServer
```

启动后浏览器访问 <http://localhost:8080/>：

- 连接建立即收到欢迎消息，同时开始静默计时
- 每条客户消息重置计时；机器人消息**不**重置（规则严格是"客户 3 分钟没说话"）
- 静默满 3 分钟：服务端先发 `closed` 系统消息（页面输入框随之禁用），再关闭连接
- 点"重新连接"即开启全新会话（无会话恢复，一连接即一会话）

## 架构

```
浏览器 ──GET /──▶ HttpServerCodec → HttpObjectAggregator ─┐（普通 HTTP：返回聊天页）
                                                          ├─ HttpStaticPageHandler
浏览器 ──GET /ws (Upgrade: websocket)──▶ 同一条 pipeline ─┤
                                                          ├─ WebSocketServerProtocolHandler("/ws") 拦截升级
                                                          └─ WebSocketChatHandler（升级后的聊天逻辑）
```

组件（全部在 `yier.bubu.playground.livechat` 包，6 个类）：

| 类 | 职责 |
|---|---|
| `ChatServer` | 启动入口（`main`）。端口与超时时长可注入；测试用 0 号端口取临时端口 |
| `HttpStaticPageHandler` | 静态页服务。启动时一次性读入 `resources/chat/index.html`，`GET /` 返回页面，其余 404/405 |
| `WebSocketServerProtocolHandler` | Netty 内置，非本项目代码。只拦截去 `/ws` 的升级请求，普通请求放行给静态页 handler |
| `WebSocketChatHandler` | 聊天核心：连接建立→欢迎+启动计时；收到客户 `chat`→重置计时+回声；超时→发 `closed`+关连接；断连→取消任务 |
| `SessionTimeoutScheduler` | 超时调度器，包住 `HashedWheelTimer`。start/touch/cancel；触发时复查剩余时间防竞态 |
| `BotResponder` | 机器人文案：欢迎语、回声 `收到：「<消息>」`、超时通知 |
| `ChatMessage` | 线格式：手写的最小 JSON envelope `{"type":"chat"\|"system"\|"closed","content":"..."}`，编解码+转义，零第三方依赖 |

前端是 `resources/chat/index.html` 一个文件：原生 `WebSocket` API，按 `type` 区分渲染（客户消息/机器人消息/系统提示/关闭通知），收到 `closed` 禁用输入框并显示重连按钮。

## 关键设计：超时与竞态处理

**为什么用 `HashedWheelTimer` 而不是 `IdleStateHandler`**：`IdleStateHandler` 的 reader idle 统计**任何**入站帧——将来加 ping/pong 心跳后，死会话会被心跳永久续命。而本需求要求"只有客户真实发言才算活跃"，所以必须自己管理"最后活跃时间"。

**竞态防护（`SessionTimeoutScheduler.onFire`）**：时间轮精度有限，任务可能比到期时刻早触发。触发时不直接关闭，而是复查 `now - lastActiveAt`：还有剩余时间就重新调度剩余时长；确实超时才执行关闭。这样"消息刚到、任务同时触发"的窗口内活跃会话不会被误杀。

**资源回收**：非超时原因断连（网络故障、客户端主动关）时 `channelInactive` 取消挂起的超时任务，不泄漏。

## 测试

```bash
mvn -pl playground/live-chat test
```

两层（全部绿色，8 个用例）：

- **`WebSocketChatHandlerTest`**（7 个，`EmbeddedChannel` + 毫秒级超时）：欢迎消息、回声、JSON 转义往返、静默超时关闭、客户消息重置计时、临近截止消息不误杀（竞态复查）、断连取消任务
- **`ChatServerEndToEndTest`**（1 个，真实端口 + JDK 原生 WebSocket 客户端）：`GET /` 拿页面 → 连 `/ws` → 欢迎语 → 回声 → 静默 → `closed` → 连接关闭，覆盖完整 HTTP→WebSocket 升级路径

测试只断言外部行为（写给客户端的帧、通道开关状态），不断言内部字段。
