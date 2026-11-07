# 清弈联机方案调研与麻将联机选型

> 调研范围：`g:\AI\清弈`（Android，Java）、`g:\AI\清弈-ios`（iOS，SwiftUI）
> 结论日期：2026-11-07
> 适用对象：准备开发「国标麻将 + 广东麻将」安卓/iOS 游戏的联机架构

---

## 0. 结论摘要

| 问题 | 结论 |
|---|---|
| 清弈有没有服务端？ | **没有**。没有账号服务、房间服务、匹配服务、战绩库，也没有任何云端部署。 |
| 它是什么联机？ | **同一局域网内的双人回合制对局**：一端当 Host（TCP Server + 权威裁判），另一端当 Guest（TCP Client），通过 Bonjour/mDNS 发现。 |
| 通信协议？ | **原始 TCP + 4 字节大端长度前缀 + UTF-8 JSON**，不是 WebSocket，不是 HTTP，不是 UDP。 |
| 是否实时帧同步？ | **不是**。它是「事件 + 权威裁决 + 快照纠偏」的回合制同步，本质是**主机权威（Authoritative Host）**，不是 lockstep/帧同步。 |
| 能否直接用于麻将联机？ | **不能直接用于线上四人麻将**。它缺服务端、匹配、鉴权、持久化、托管、观战、反作弊，且只支持 2 人、只支持局域网、无 TLS。 |
| 哪些能复用？ | 协议信封设计、长度前缀帧、心跳/重连/宽限期、`resumeToken`、`state_snapshot`、`seq` 防丢序、棋种适配器（`GameAdapter`）分层思路、Host 权威的判定模型。 |
| 推荐方案？ | **自建权威服务端 + WebSocket**（Kotlin/Ktor 或 Go），客户端只发意图、服务端唯一裁决；Redis 存房间热状态，PostgreSQL 存账号/战绩/牌谱。详见 §7。 |

一句话：**清弈是一份很好的「回合制权威同步」协议范本，但不是一套可上线的麻将联机后端。**

---

## 1. 清弈的事实清单

### 1.1 技术栈与工程形态

| 维度 | Android (`g:\AI\清弈`) | iOS (`g:\AI\清弈-ios`) |
|---|---|---|
| 语言/UI | Java + 经典 View/XML（**不是 Compose**） | Swift 5.7 + SwiftUI |
| 构建 | Gradle，单 `app` 模块 | XcodeGen（`project.yml`）→ Xcode |
| 最低系统 | minSdk 28 / targetSdk 34 | iOS 16.0 |
| 联机代码位置 | [net 包](file:///g:/AI/清弈/app/src/main/java/com/sijia/go/net) | [QingYi/Net](file:///g:/AI/清弈-ios/QingYi/Net) |
| 网络依赖 | 仅系统 `NsdManager` + `java.net` + `org.json` | 仅 `Network.framework` + `Foundation` |
| 服务端 | **无** | **无** |
| 数据库 | **无** | **无** |

### 1.2 部署方式

- **没有服务器部署**。Host 就是「房间服务器」，跑在玩家手机上。
- Host 侧 `ServerSocket` 监听 `41234`，被占用则顺延到 `41244`；实际端口显示在 UI 上。
- Guest 通过 mDNS 自动发现，或手动输入 `IP:端口` 加入。
- 局域网外无法对战；跨 NAT、4G/5G、不同网段均不支持。

关键代码：
- [LanHost.java](file:///g:/AI/清弈/app/src/main/java/com/sijia/go/net/LanHost.java)：TCP 服务、端口顺延、单客机槽位、握手超时。
- [LanClient.java](file:///g:/AI/清弈/app/src/main/java/com/sijia/go/net/LanClient.java)：TCP 客户端、读写线程分离。
- [LanDiscovery.java](file:///g:/AI/清弈/app/src/main/java/com/sijia/go/net/LanDiscovery.java)：Bonjour/NSD 注册与浏览。
- [LanHub.java](file:///g:/AI/清弈/app/src/main/java/com/sijia/go/net/LanHub.java)：房间状态机、权威裁决、时钟、重连。

### 1.3 通信协议

**发现层**

| 项 | 值 |
|---|---|
| 服务类型 | `_qingyi-go._tcp` / `_qingyi-xiangqi._tcp` |
| 服务实例名 | `QY-<roomCode>-<size>-<komi>-<state>-<encName>` |
| 端口 | 41234 起顺延 |
| 房间码 | 4 位 Crockford Base32（去掉 I/L/O/U） |

**传输层**

- 原始 TCP，仅 IPv4。
- 帧格式：`uint32 length (big-endian)` + `length` 字节 UTF-8 JSON。
- 单帧上限 64 KiB。
- 不做 TLS；房间码即入房凭证。
- 无 HTTP 握手、无 WebSocket Upgrade。

**消息信封**（见 [lan-multiplayer-design.md](file:///g:/AI/清弈/docs/lan-multiplayer-design.md) §3.1）

```json
{
  "v": 1,
  "t": "move",
  "sid": "9f1c2a7e",
  "ts": 1762473600123,
  "seq": 17,
  "room": "7K2M",
  "from": "guest",
  "p": {}
}
```

| 字段 | 作用 |
|---|---|
| `v` | 协议版本，不匹配直接拒绝 |
| `t` | 消息类型 |
| `sid` | 消息 ID，请求/响应关联 |
| `ts` | 发送时间戳，仅日志/时偏估计 |
| `seq` | 连接内单调递增，丢序检测 |
| `room` | 房间码 |
| `from` | `host` / `guest` |
| `p` | 业务负载 |

**消息清单**：`hello` / `hello_ack` / `hello_reject` / `lobby` / `ready` / `game_start` / `move` / `move_ack` / `move_reject` / `state_snapshot` / `undo_request` / `undo_response` / `resign` / `draw_offer` / `draw_response` / `clock` / `ping` / `pong` / `game_over` / `chat` / `bye`。

**心跳与断线**

| 项 | 值 |
|---|---|
| 心跳间隔 | 5 s（`LINK_PING_MS`） |
| 链路判死 | 13 s 无任何消息（`LINK_IDLE_MS`） |
| 重连宽限 | 120 s（`RECONNECT_GRACE_MS`），期间**暂停双方时钟** |
| 重连凭证 | `resumeToken` + `matchId` + 手数 |
| 重连后 | Host 下发 `state_snapshot`，Guest 恢复棋盘与时钟 |
| 宽限到 | 判断线方负 |

关键代码：[LanHub.java](file:///g:/AI/清弈/app/src/main/java/com/sijia/go/net/LanHub.java#L108-L113)、[LanSession.java](file:///g:/AI/清弈/app/src/main/java/com/sijia/go/net/LanSession.java)

**防不同步**

- Host 是唯一权威；Guest 的落子只是「意图」。
- 每条 `move` 带 `hashBefore`，Host 校验棋盘哈希，不匹配返回 `E_HASH_MISMATCH` 并附快照。
- `moveNo` 显式递增，**不读两端内部的 `moveCount`**（两端语义不同）。
- 断线重连或分歧时用 `state_snapshot` 全量覆盖。

### 1.4 房间与匹配

| 能力 | 清弈现状 |
|---|---|
| 创建房间 | Host 一键创建，生成 4 位房间码 |
| 加入房间 | Bonjour 列表选择，或手动 IP:端口 |
| 房间容量 | **1 Host + 1 Guest，共 2 人** |
| 匹配 | **无**。没有匹配池、没有段位、没有场次 |
| 座位 | `seat 0/1`，固定两席 |
| 准备 | Guest `ready`，Host 点开始 |
| 观战 | **无** |
| 再来一局 | **无**（`LanHub` 中无 rematch 流程） |
| 房间列表 | 仅局域网 Bonjour 广播，无中心化列表 |

### 1.5 断线重连与托管

- **重连**：有。Guest 自动重连，Host 保留会话与棋盘，宽限期内暂停时钟。
- **托管**：**无**。没有「掉线后 AI 代打」；只有「暂停 → 等待 → 超时判负」。
- **主动离开**：`bye`，对局中离开等同认输（需二次确认）。

对麻将来说，「暂停等 120 秒」不可接受：另外三家会一直卡住。麻将必须做**掉线托管**，而不是纯暂停。

### 1.6 战绩与回放

- **服务端战绩：无。**
- **回放：无服务端回放。** 仅本地保存 SGF 棋谱（`Sgf.java` / `Sgf.swift`）。
- 没有对局 ID、没有事件日志、没有牌谱分享链接、没有统计页。

### 1.7 鉴权

- **无账号体系。**
- 唯一门槛是 4 位房间码。
- 重连用 `resumeToken`（会话令牌），不是身份认证。
- 无 JWT、无 OAuth、无设备绑定、无防重放。

### 1.8 AI 参与

- 联机时**只允许一方跑 AI**（Host 或 Guest），AI 的落子当作普通 `move` 广播。
- 原因是两端引擎实现不同（Android 子进程 GTP，iOS 静态链接 C++），同等级走子不一致。
- 这个「AI 结果必须经权威方落子」的原则，麻将同样适用。

---

## 2. 架构性质判定

### 2.1 它到底是什么

```
         mDNS 发现                    原始 TCP（长度前缀 JSON）
┌──────────────────┐  ◀──────────────────────────────────────▶  ┌──────────────────┐
│  Host（手机）     │                                              │  Guest（手机）    │
│  ├ 权威棋局       │   hello / move / move_ack / snapshot         │  ├ 镜像棋局       │
│  ├ 权威时钟       │   ping / pong / clock                         │  ├ 插值显示时钟   │
│  ├ TCP ServerSocket│                                              │  ├ TCP Client     │
│  └ 可选 AI         │                                              │  └ 不跑 AI        │
└──────────────────┘                                              └──────────────────┘
```

判定：**事件驱动的回合制权威主机同步（turn-based authoritative host）**。

它不是：
- 不是帧同步 / lockstep（不需要每帧广播输入）；
- 不是状态同步（不是每 tick 广播全状态）；
- 不是 WebSocket 长连接；
- 不是 C/S 服务端架构（没有独立服务端）。

但它**抽象上**和「权威服务端 + 客户端意图」是同一类：Host 就是「跑在玩家设备上的服务端」。所以它的**协议思想可复用**，**部署形态不可复用**。

### 2.2 麻将与围棋/象棋的关键差异

| 维度 | 清弈（围棋/象棋） | 麻将（国标/广东） |
|---|---|---|
| 人数 | 2 | **4** |
| 信息 | 完全公开 | **手牌隐藏、牌墙隐藏**（存在私有状态） |
| 回合 | 严格交替 | 摸打 + 吃碰杠胡的**插入式响应窗口** |
| 并发 | 无 | 一张牌可能**多家同时可胡/碰/杠**，需要优先级与超时 |
| 决策 | 单一落子 | 摸牌、出牌、吃、碰、杠、胡、过、补花、买马 |
| 断线 | 可暂停等待 | **必须托管**，否则三家被卡 |
| 反作弊 | 公开棋盘，作弊空间小 | **服务端必须隐藏牌墙与对手手牌**，客户端不能拿到全量状态 |
| 回放 | SGF 足够 | 需要**种子 + 完整动作流**才能复现 |
| 计分 | 数子 | 番种组合、封顶、包牌、买马，规则分支多 |

结论：麻将的联机复杂度显著高于围棋/象棋，**清弈的 2 人 Host/Guest 模型不能直接扩展成 4 人线上房**。

---

## 3. 可复用点与不适用点

### 3.1 可直接复用（设计层面）

| 复用点 | 说明 | 麻将落地方式 |
|---|---|---|
| 长度前缀帧 | 4 字节大端 + JSON，简单、跨语言 | 可保留；WebSocket 二进制帧内再套同样信封 |
| 消息信封 | `v/t/sid/ts/seq/room/from/p` | 改为 `v/t/sid/ts/seq/room/seat/p`，`seat` 由服务端下发 |
| `seq` 防丢序 | 每条连接单调递增 | 保留；服务端按 `seq` 丢弃重复/乱序 |
| `sid` 请求关联 | 请求/响应配对 | 保留；用于 `action` / `action_result` |
| `ping/pong` + RTT | 5 s 心跳、时偏估计 | 保留；麻将还需要**响应窗口倒计时** |
| `resumeToken` | 断线重连凭证 | 改为服务端签发的 `sessionToken` + `reconnectToken` |
| `state_snapshot` | 全量纠偏 | 保留；但**快照必须按座位裁剪**，不能把别人的手牌发给你 |
| Host 权威裁决 | 单一事实来源 | 改为**服务端权威**，思想一致 |
| 规则适配器 | `GameAdapter` 隔离棋种 | 复用为 `RuleSet` 接口：国标/广东两套实现 |
| 悔棋/求和协议 | 请求-应答 | 麻将一般不支持悔棋；求和/流局可借鉴 |
| 时钟暂停/恢复 | 断线冻结 | 麻将改为「短暂冻结 + 超时托管」 |

### 3.2 明确不适用（不能照搬）

| 不适用点 | 原因 | 麻将需要什么 |
|---|---|---|
| 无服务端 | 手机 Host 无法做匹配、鉴权、持久化、反作弊 | 独立权威服务端 |
| 局域网 TCP | 4G/5G、跨 NAT、不同网络无法连 | 公网 WebSocket（wss） |
| 2 人座位模型 | 麻将是 4 人 | 4 座 + 观战席 |
| 明文 + 4 位房间码 | 可被嗅探/猜房 | TLS + 账号/JWT + 房间密码/邀请 |
| 无账号 | 无法做段位、战绩、好友、举报 | 账号体系（游客/Apple/Google/微信） |
| 无匹配 | 没有大厅/场次 | 匹配服务 + 场次/段位 |
| 无持久化 | 重启即丢 | PostgreSQL + 事件日志 |
| 无回放 | 只有本地 SGF | 服务端动作日志 + 种子复现 |
| 无托管 | 断线只能等 | 15–30 s 后 AI 托管 |
| 无观战 | 只有两人 | 观战延迟流 + 牌谱 |
| 全量状态可见 | 双方都知道全部棋盘 | 按座位裁剪的私有视图 |
| 无并发响应 | 严格交替 | 响应窗口 + 优先级 + 超时默认过 |

---

## 4. 麻将联机的硬需求

在选型前先锁定这些约束，否则会被「实时框架」带偏：

1. **服务端权威**：牌墙、骰子、发牌、摸牌顺序必须由服务端生成；客户端只发「我要打哪张 / 我要碰」。
2. **私有信息**：每个客户端只能收到自己的手牌 + 公开副露 + 牌河 + 剩余牌数；不能下发完整牌墙。
3. **四人同步**：一次出牌后，最多三家有响应权（胡 > 杠/碰 > 吃），需要响应窗口与优先级。
4. **可重连**：移动网络切换、锁屏、来电都会断；重连后必须恢复到「当前手牌 + 当前响应窗口」。
5. **可托管**：掉线超过 N 秒，AI 接管；玩家回来立即取消托管。
6. **可回放**：一局 = 种子 + 动作序列；服务端存事件日志，客户端可拉取复盘。
7. **可扩展规则**：国标 81 番、广东推倒胡/鸡胡/买马，同一套引擎用配置切换。
8. **可反作弊**：所有合法性、番种、计分在服务端复算；客户端结果只用于展示。
9. **可运营**：账号、段位、场次、任务、好友房、举报、封禁。

---

## 5. 候选方案对比

| 方案 | 权威性 | 四人房间 | 私有信息 | 重连/托管 | 运维成本 | 成本 | 适用结论 |
|---|---|---|---|---|---|---|---|
| **A. 自建权威服务端 + WebSocket** | 强 | 原生支持 | 服务端裁剪 | 完全可控 | 中 | 低（一台小 VPS 起步） | **推荐** |
| B. Supabase Realtime | 弱（需 Edge Function/DB 补） | 可 | 需自己写 RLS/视图 | 需自己实现 | 低 | 低—中 | 适合原型，不适合强竞技 |
| C. Firebase Realtime DB / Firestore | 弱（客户端可写） | 可 | 规则难做细 | 需自己实现 | 低 | 中（按量） | 不推荐做权威对局 |
| D. Photon（Realtime/PUN/Quantum） | 中（Room 内可做权威逻辑） | 可 | 需自建房间逻辑 | SDK 提供部分 | 低 | 中—高（CCU 计费） | 可做，但锁定与成本高 |
| E. WebRTC P2P + 信令 | 无（或主机权威） | 可 | 主机可见全部 | 复杂 | 中 | 低 | 只适合好友房，不适合排位 |
| F. 云游戏服务器引擎（GSE 等） | 强 | 可 | 可 | 可 | 高 | 高 | 规模大后再考虑 |

### 5.1 为什么推荐 A

- 麻将是**低频、强逻辑、强权威**的回合制游戏，不需要 Photon 那种高频状态同步。
- WebSocket 一条长连接足够；消息量小（一局几百条），单机可承载大量房间。
- 规则引擎可**客户端/服务端共用**（KMP 共享核心），服务端复算成本低。
- 反作弊、回放、托管、观战都能在服务端一处实现。
- 初期一台 2C4G VPS + Docker 即可；后续按房间分片。

### 5.2 为什么不用 Supabase/Firebase 做主对局

- 它们的强项是「数据同步」，不是「权威游戏循环」。
- 客户端直写数据库意味着**客户端能改状态**；用 Edge Function 包一层后，你又回到了「自建服务端」，只是多了一层限制。
- 房间内的响应窗口、超时、托管、优先级，用数据库事务/规则表达会很别扭。
- 可作为**账号、好友、战绩、排行榜**的辅助后端，但不建议承载对局循环。

### 5.3 为什么不用 Photon 类托管

- 可以，但：CCU 计费、逻辑绑定 SDK、私有信息裁剪要自己做、回放/战绩仍要另建后端。
- 如果团队完全不想碰服务器运维，Photon 是可接受的备选；否则自建更可控。

---

## 6. 推荐架构

### 6.1 拓扑

```
┌────────────┐   wss (JSON)   ┌──────────────────────────────┐
│ Android    │◀──────────────▶│  Gateway（WebSocket 接入）     │
│ Compose    │                │  ├ 鉴权 / 限流 / 心跳          │
└────────────┘                │  └ 按 roomId 路由到 RoomActor  │
┌────────────┐                ├──────────────────────────────┤
│ iOS        │◀──────────────▶│  Room Service（权威对局循环）  │
│ SwiftUI/CMP│                │  ├ 发牌 / 摸打 / 吃碰杠胡      │
└────────────┘                │  ├ 响应窗口 / 优先级 / 超时    │
                              │  ├ 托管 AI                    │
                              │  └ 结算 / 番种 / 买马          │
                              ├──────────────────────────────┤
                              │  Redis：房间热状态 / 匹配池    │
                              │  PostgreSQL：账号/战绩/牌谱    │
                              │  对象存储：回放 JSON（可选）   │
                              └──────────────────────────────┘
```

### 6.2 服务拆分（MVP 可合并为一个进程）

| 模块 | 职责 | 建议实现 |
|---|---|---|
| Gateway | WS 连接、鉴权、心跳、消息编解码 | Ktor WebSocket 或 Go + gorilla/websocket |
| Account | 游客/Apple/Google 登录、JWT | Ktor + PostgreSQL |
| Lobby | 场次列表、快速匹配、好友房 | Redis 匹配池 |
| Room | 房间生命周期、座位、准备、开始 | 单房间单 Actor（串行处理，避免并发） |
| Game | 规则引擎、动作校验、番种、结算 | 纯函数式核心，可单测 |
| Replay | 事件日志、牌谱读取 | PostgreSQL JSONB / 对象存储 |
| Anti-cheat | 服务端复算、异常行为 | 同一规则引擎复算 |

### 6.3 关键设计

**1）单房间串行**

每个房间用一个 actor/协程串行处理消息，天然避免「两家同时碰」的竞态。所有动作进入队列，按到达顺序 + 规则优先级处理。

**2）响应窗口**

```text
玩家 A 打出 X
  → 服务端开启窗口（例如 5 s）
  → 按优先级收集：胡 > 杠 > 碰 > 吃
  → 最高优先级中，按逆时针座位顺序取第一个（国标一炮单响）
  → 超时未响应 = 过
  → 广播 action_result + 各自私有视图
```

**3）私有视图**

服务端为每个座位生成不同 payload：

```json
{
  "t": "state",
  "p": {
    "seat": 2,
    "hand": ["1m","2m","3m","..."],
    "melds": [{"type":"peng","tile":"5p","from":1}],
    "discards": {"0":["..."],"1":["..."],"2":["..."],"3":["..."]},
    "wallCount": 42,
    "turn": 2,
    "window": {"type":"discard_response","deadline":1762473605000},
    "scores": [25000,25000,25000,25000]
  }
}
```

**绝不下发**：牌墙顺序、对手手牌、骰子未公开部分、买马牌（结算前）。

**4）重连**

```text
断线 → 服务端标记 offline，启动 15–30 s 宽限
     → 宽限内重连：下发 sync（私有视图 + 当前窗口剩余时间）
     → 宽限到：AI 托管，玩家回来取消
```

与清弈的差异：**不暂停整局**，只对掉线者托管。否则三家被卡死。

**5）回放**

一局存储：

```json
{
  "gameId": "...",
  "ruleSet": "mcr",
  "seed": "hex",
  "seats": [{"userId":...}, ...],
  "actions": [
    {"seq":1,"seat":0,"type":"discard","tile":"1m"},
    {"seq":2,"seat":1,"type":"peng","tile":"1m"},
    {"seq":3,"seat":1,"type":"discard","tile":"9p"}
  ],
  "result": {"winner":2,"fan":["清一色","碰碰和"],"points":24}
}
```

客户端用同一规则引擎重放即可，不需要存每一帧。

**6）鉴权**

- 启动：设备游客 ID → 服务端签发 `accessToken`（JWT，短期）+ `refreshToken`。
- 可选绑定 Apple/Google/微信。
- WS 握手带 token；服务端校验后分配 `seat`。
- 重连带 `reconnectToken`（一次性/短时效），防止顶号。

### 6.4 协议草案（麻将）

信封沿用清弈，字段微调：

```json
{
  "v": 1,
  "t": "action",
  "sid": "a1b2c3d4",
  "ts": 1762473600123,
  "seq": 42,
  "room": "R7K2M",
  "seat": 2,
  "p": {}
}
```

| 类型 | 方向 | 说明 |
|---|---|---|
| `login` | C→S | token 登录 |
| `lobby_join` | C→S | 进场次/匹配 |
| `room_create` / `room_join` | C→S | 好友房 |
| `room_state` | S→C | 座位、准备、房主 |
| `game_start` | S→C | 规则集、座位、庄家、起手手牌（私有） |
| `action` | C→S | `discard/chi/peng/gang/hu/pass/buy_horse` |
| `action_result` | S→C | 是否接受 + 新状态（私有裁剪） |
| `state` | S→C | 私有视图（重连/关键节点全量） |
| `window` | S→C | 响应窗口开启/关闭 + deadline |
| `game_over` | S→C | 结算、番种、分数变化 |
| `heartbeat` | 双向 | 5 s |
| `reconnect` | C→S | token + gameId |
| `sync` | S→C | 重连全量私有视图 |
| `chat` / `emoji` | 双向 | 可选 |

### 6.5 与清弈的对应关系

| 清弈 | 麻将新架构 |
|---|---|
| `LanHost`（手机 TCP Server） | Gateway + RoomActor（服务端） |
| `LanClient` | 客户端 WS 连接 |
| `hello/hello_ack` | `login` + `room_join` + `game_start` |
| `move/move_ack` | `action/action_result` |
| `state_snapshot` | `state/sync`（按座位裁剪） |
| `clock` | `window` + 回合计时 |
| `resumeToken` | `reconnectToken` |
| `LanSession.applyAuthority` | 服务端 `Game.apply(seat, action)` |
| `GameAdapter` | `RuleSet`（MCR / Guangdong） |

---

## 7. 落地阶段建议

| 阶段 | 目标 | 验证点 |
|---|---|---|
| P0 | 纯本地规则引擎（无网络） | 国标/广东两套规则单测通过；番种、结算可复算 |
| P1 | 单机 4 人（3 AI） | 完整一局能打完；托管 AI 可接管 |
| P2 | 服务端房间 + WS | 4 个客户端同房；服务端权威发牌/校验 |
| P3 | 断线重连 + 托管 | 杀进程/切网后 30 s 内恢复 |
| P4 | 匹配/场次/账号 | 游客登录、快速匹配、战绩落库 |
| P5 | 回放/观战/举报 | 牌谱可回放；观战延迟 1–2 手 |

**不要**在 P0 之前接任何实时框架；规则引擎是所有后续工作的地基。

---

## 8. 需要决策的岔路

1. **是否首期就做线上联机？** 建议先做「单机 + 好友房」，把规则引擎和 AI 打稳，再做匹配。
2. **服务端语言？** 若客户端用 KMP，建议服务端也用 Kotlin/Ktor，规则引擎可直接共享；若团队更熟 Go，则规则用 JSON 测试向量对齐。
3. **是否用 Supabase 做账号/战绩？** 可以，但**对局循环不要放进去**。
4. **好友房是否要 6 位房号 + 密码？** 建议 6 位房号 + 可选密码，避免 4 位被猜。
5. **托管 AI 用哪一级？** 建议「普通」级，保证不点炮太离谱，也不至于太强。

---

## 附：清弈关键文件索引

| 作用 | 文件 |
|---|---|
| 联机设计总文档 | [lan-multiplayer-design.md](file:///g:/AI/清弈/docs/lan-multiplayer-design.md) |
| Android Host | [LanHost.java](file:///g:/AI/清弈/app/src/main/java/com/sijia/go/net/LanHost.java) |
| Android Guest | [LanClient.java](file:///g:/AI/清弈/app/src/main/java/com/sijia/go/net/LanClient.java) |
| Android 房间/权威 | [LanHub.java](file:///g:/AI/清弈/app/src/main/java/com/sijia/go/net/LanHub.java) |
| Android 会话 | [LanSession.java](file:///g:/AI/清弈/app/src/main/java/com/sijia/go/net/LanSession.java) |
| Android 时钟 | [LanClock.java](file:///g:/AI/清弈/app/src/main/java/com/sijia/go/net/LanClock.java) |
| Android 发现 | [LanDiscovery.java](file:///g:/AI/清弈/app/src/main/java/com/sijia/go/net/LanDiscovery.java) |
| iOS 协议 | [LanProto.swift](file:///g:/AI/清弈-ios/QingYi/Net/LanProto.swift) |
| iOS 帧编解码 | [LanFrame.swift](file:///g:/AI/清弈-ios/QingYi/Net/LanFrame.swift) |
| iOS 房间/权威 | [LanHub.swift](file:///g:/AI/清弈-ios/QingYi/Net/LanHub.swift) |
| iOS Host | [LanHost.swift](file:///g:/AI/清弈-ios/QingYi/Net/LanHost.swift) |
| iOS Guest | [LanClient.swift](file:///g:/AI/清弈-ios/QingYi/Net/LanClient.swift) |
| iOS 发现 | [LanDiscovery.swift](file:///g:/AI/清弈-ios/QingYi/Net/LanDiscovery.swift) |
