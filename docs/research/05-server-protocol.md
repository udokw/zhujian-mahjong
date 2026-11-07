# 联机服务端与协议（v1）

> 目录：`g:\AI\麻将\server`
> 状态：v1 已实现（好友房 + 4 人 WS 对局 + 裁剪视图 + 响应窗口 + 断线重连/托管 + 牌谱落库）
> 上游依据：[01 清弈联机方案](computer://g:\AI\麻将\docs\research\01-qingyi-online-solution.md)

---

## 0. 结论与边界

| 项 | 决定 |
|---|---|
| 拓扑 | **权威服务端**（Kotlin + Ktor WebSocket）。客户端只发意图，服务端裁决并推进牌局。 |
| 复用清弈的部分 | 消息信封、连接内单调 `seq`、`resumeToken` 续局、`state_snapshot` 全量纠偏 |
| 明确不复用 | 手机当 Host、局域网 TCP、2 人座位、无裁剪全量状态、纯暂停式断线 |
| 单房间并发模型 | 房间内所有变更串行执行（互斥锁），天然避免「两家同时碰」的竞态 |
| 私有信息 | 每个座位收到**不同的** `state`：自己的手牌 + 公开副露/牌河 + 各家手牌数量；牌墙顺序永不下发 |
| 反作弊 | 合法性与番型/得分全部服务端复算；客户端上报的任何分数/番型直接丢弃 |
| 持久化 | 每局存「规则配置 + 随机种子 + 已接受动作序列」，可完整复现；默认 SQLite，Postgres 通过环境变量切换 |
| 匹配 | v1 只做好友房；`Matchmaker` 留接口，未接入 |

---

## 1. 传输层

- 单条 WebSocket 长连接：`ws://<host>:<port>/ws`（生产走 `wss://` 由反向代理终止 TLS）。
- 每帧一个 UTF-8 JSON 对象（文本帧），不嵌套长度前缀；单帧上限 64 KiB，超限断开。
- 心跳：客户端每 5 s 发 `ping`；服务端 15 s 未收到任何消息判定连接死亡。
- 连接死亡 ≠ 座位释放：见 §7。

### 1.1 信封

客户端 → 服务端：

```json
{
  "v": 1,
  "t": "action",
  "sid": "a1b2c3d4",
  "ts": 1762473600123,
  "cseq": 42,
  "room": "R7K2M9",
  "seat": 2,
  "p": { "action": "discard", "tile": 4 }
}
```

服务端 → 客户端：

```json
{
  "v": 1,
  "t": "state",
  "sid": "",
  "ts": 1762473600200,
  "sseq": 128,
  "room": "R7K2M9",
  "seat": 2,
  "p": {}
}
```

| 字段 | 方向 | 说明 |
|---|---|---|
| `v` | 双向 | 协议版本，固定 `1`；不匹配返回 `E_BAD_VERSION` 并关闭 |
| `t` | 双向 | 消息类型（snake_case） |
| `sid` | 双向 | 请求关联 ID。客户端生成；服务端在响应中回填同一个 `sid`，事件/广播为 `""` |
| `ts` | 双向 | 发送方毫秒时间戳，仅用于 RTT/日志，服务端不信任 |
| `cseq` | C→S | 连接内单调递增（从 1 开始）。服务端丢弃 `cseq <= lastSeen` 的重复消息 |
| `sseq` | S→C | **房间事件序号**，房间内全局单调递增。客户端发现跳号即发 `sync_request` |
| `room` | 双向 | 房间码；未入房时为空串 |
| `seat` | 双向 | S→C 时为目标座位（0..3）；C→S 时忽略，座位以服务端会话为准 |
| `p` | 双向 | 业务负载，见下 |

两个序号语义不同，故意分开命名：`cseq` 防重放/去重，`sseq` 做丢包检测与快照纠偏。

`state` / `event` / `room_state` / `game_over` / `seat_online` 等**公共广播**每发一轮 `sseq += 1`。
`seat_granted` / `window` / `action_ack` / `sync` / `error` 属于**私有消息**，沿用当前 `sseq` 不单独递增——
否则公共消息的序号会出现空洞，客户端无法区分「只是私有消息插队」还是「真的丢包」。
客户端只需对公共广播校验单调性；私有消息按连接 FIFO 处理即可。

### 1.2 牌值编码

与 core 的 `mahjong.core.tile.Tiles` 完全一致，线上直接传整数：

| 范围 | 含义 |
|---|---|
| `0..8` | 一万..九万 |
| `9..17` | 一筒..九筒 |
| `18..26` | 一条..九条 |
| `27..30` | 东 南 西 北 |
| `31..33` | 中 发 白 |
| `34..41` | 春 夏 秋 冬 梅 兰 竹 菊（花牌） |

客户端可直接复用 core 的 `Tiles.name()` 做展示。

---

## 2. 连接与身份

v1 无账号体系，用游客身份（`playerId` 由服务端按 `deviceId` 派生并缓存），重连靠 `resumeToken`。

| 消息 | 方向 | 负载 | 说明 |
|---|---|---|---|
| `hello` | C→S | `{deviceId, nickname}` | 连接后第一条；重复 `hello` 返回 `E_ALREADY_AUTHED` |
| `hello_ack` | S→C | `{playerId, nickname, serverTime}` | 认证成功 |
| `ping` | C→S | `{time}` | |
| `pong` | S→C | `{time, serverTime}` | 回填客户端 `time` 用于 RTT |

未 `hello` 前只接受 `hello` / `ping`，其余消息返回 `E_UNAUTHENTICATED`。

---

## 3. 房间（好友房）

### 3.1 房号

6 位 Crockford Base32（去掉 `I/L/O/U`），服务端生成并保证当前进程内不重复。可选 4–8 位密码，服务端只存 `SHA-256(salt + password)`。

### 3.2 房间消息

| 消息 | 方向 | 负载 |
|---|---|---|
| `room_create` | C→S | `{ruleSet:"MCR"\|"GD_TUIDAOHU"\|"GD_JIPINGHU", password?}` |
| `room_join` | C→S | `{room, password?}` |
| `room_leave` | C→S | `{}` |
| `room_ready` | C→S | `{ready:true\|false}` |
| `seat_add_ai` | C→S | `{}` 或 `{seat:0..3}`（房主） |
| `seat_remove_ai` | C→S | `{seat:0..3}`（房主） |
| `seat_kick` | C→S | `{seat:0..3, reason?}`（房主，移出真人玩家） |
| `game_start` | C→S | `{}`（房主，4 座满且全部真人已准备） |
| `seat_granted` | S→C | `{seat, resumeToken}`（入座/换座后私发） |
| `room_state` | S→C | 见下，房间内广播 |
| `seat_online` | S→C | `{seat, online}`，在线状态变化时广播 |
| `room_closed` | S→C | `{reason}` |
| `room_kicked` | S→C | `{reason, bySeat}`，被踢者私发，**先于连接关闭** |

`room_state.p`：

```json
{
  "room": "R7K2M9",
  "ruleSet": "MCR",
  "hostSeat": 0,
  "phase": "waiting",
  "seats": [
    {"seat":0,"kind":"human","playerId":"p-ab12","nickname":"阿东","ready":true,
     "online":true,"aiLevel":0,"score":100},
    {"seat":1,"kind":"ai","nickname":"AI·普通","ready":true,"online":true,
     "aiLevel":2,"score":100},
    {"seat":2,"kind":"empty","nickname":"","ready":false,"online":false,
     "aiLevel":0,"score":100}
  ],
  "gameId": null
}
```

规则：座位 0..3 即 东南西北。创建者坐第一个空位（0）。`game_start` 需 4 座无空且真人全部 `ready`；AI 自动视为准备。`room_leave` 在对局中等于认输并转为 AI，不在对局中则空出座位。

### 3.3 规则预设

`ruleSet` 只决定 core `RuleConfig` 的预设，客户端不传任意规则字段（防止改规则）：

| `ruleSet` | core 预设 | 说明 |
|---|---|---|
| `MCR` | `RuleConfig.of(RulePreset.MCR_1998)` | 国标，8 番起和 |
| `GD_TUIDAOHU` | `RuleConfig.of(RulePreset.GD_TUIDAOHU)` | 广东推倒胡 |
| `GD_JIPINGHU` | `RuleConfig.of(RulePreset.GD_JIPINGHU)` | 广东鸡平胡 |

别名：`GUANGDONG` / `GD` → `GD_TUIDAOHU`，`JIPINGHU` → `GD_JIPINGHU`。
未知取值返回 `E_BAD_JSON`。聊天、观战等房间级配置在 v1 之后再加。

序列化约定：`online=false`、`ready=false` 等默认值会被省略（`encodeDefaults=false`），客户端按字段缺省值处理；因此 `room_state` 里没有 `online` 字段即代表离线。

### 3.4 房主移出玩家（`seat_kick`）

房主在玩家列表里对任一**真人**座位点击「移出」；服务端校验后：

1. 先向被踢者私发 `room_kicked{reason, bySeat}`（`sseq=0`），随后主动关闭该连接，客户端据此弹出「你已被房主移出房间」并清空断线重连令牌。
2. 被踢者的 `resumeToken` 立即作废，无法再 `reconnect` 回原座位。
3. 等待阶段（`waiting`）：座位立刻置空、准备状态清空，并广播 `room_state`，新玩家可立即加入该座位。
4. 对局中（`playing`）：不打断牌局，座位保留并标记离线，`resigned=true` + `aiControlled=true`，由二级 AI 打到本局结束；本局结束后座位自动释放。
5. 成功后房主会先收到一条 `room_state`，再收到 `action_ack{ok=true, sid}`。

边界处理：

| 场景 | 行为 |
|---|---|
| 重复踢同一座位（等待阶段，座位已空） | 幂等成功，返回 `action_ack(ok)`，不报错、不再广播额外通知 |
| 重复踢同一座位（对局中，座位仍标记为真人） | 幂等成功，重复置离线/托管，不影响牌局 |
| 被踢玩家已断线 | 正常移出并释放座位（无需对方在线，也无法再收到提示） |
| 踢 AI 座位 | `E_NOT_A_PLAYER`，请改用 `seat_remove_ai` |
| 踢自己 | `E_CANNOT_KICK_SELF` |
| 非房主踢人 | `E_NOT_HOST` |
| 房主自己退出（`room_leave`） | 等待阶段：连接关闭并释放座位，房主转移给下一位在线真人；对局中：座位转 AI 打到本局结束，房主转移给其余在线真人，连接不关闭。房间最后一人离开后自动关闭。 |

### 3.5 局域网自动发现（UDP）

服务端默认监听 **UDP 45231**（`MAHJONG_DISCOVERY_PORT`，`MAHJONG_DISCOVERY=off` 可关闭），无需 Bonjour/mDNS，纯 UDP 广播即可跨平台：

| 报文 | 方向 | 内容 |
|---|---|---|
| 查询 | 客户端 → 广播地址 | 文本 `MJ?1` |
| 应答 | 服务端 → 查询方（单播） | 文本 `MJ_ROOMS/1` + `\n` + JSON |
| 主动通告 | 服务端 → `255.255.255.255` 与各网卡广播地址，每 2s | 与应答相同 |

应答 JSON：

```json
{
  "v": 1,
  "host": "192.168.1.23",
  "wsPort": 8080,
  "discoveryPort": 45231,
  "rooms": [
    {"code":"R7K2M9","ruleSet":"GD_JIPINGHU","phase":"waiting","host":"阿东",
     "players":2,"humans":2,"capacity":4,"hasPassword":false}
  ]
}
```

要点：

- **创建即被发现**：房间创建（房主入座）后立刻刷新摘要，下一次查询/通告即可见；不必等到 4 人满。
- 客户端以 UDP 包源地址拼 `ws://<源地址>:<wsPort>/ws`，多网卡机器也能拿到真正可达的 IP。
- 客户端每次在大厅广播查询并收集 1.2s 应答，按 `地址:端口/房号` 去重；进入房间后停止扫描。
- 服务端 UDP 端口被占用时只打日志降级，不影响 WebSocket 主服务。
- iOS 需在 `Info.plist` 声明 `NSLocalNetworkUsageDescription`（已加），首次扫描会弹本地网络权限。

---

## 4. 对局消息

| 消息 | 方向 | 说明 |
|---|---|---|
| `game_start` | S→C | **每个人一条**，含私有起手手牌 |
| `state` | S→C | **每人一条**裁剪视图；每次牌局推进后下发 |
| `event` | S→C | 离散事件（摸/打/吃/碰/杠/和/流局），逐条裁剪后下发 |
| `window` | S→C | 响应窗口，仅发给有响应权的座位 |
| `action_ack` | S→C | 意图受理结果（`sid` 回填） |
| `game_over` | S→C | 服务端复算后的结算 |
| `sync` | S→C | 重连/纠偏全量快照 |

### 4.1 `game_start`

```json
{
  "gameId": "g-20261107-7f3a",
  "ruleSet": "MCR",
  "seed": "9f1c2a7e5b",
  "dealer": 0,
  "roundWind": 0,
  "seatWind": 2,
  "initialHand": [0,1,2,9,10,18,27,28,31,4,5,6,7],
  "scores": [100,100,100,100],
  "wallRemaining": 83,
  "flowers": [34]
}
```

`seed` 对客户端只作展示（可用于本地复盘校验），牌墙由服务端持有。

### 4.2 `state`（按座位裁剪）

```json
{
  "gameId": "g-20261107-7f3a",
  "turn": 12,
  "phase": "AWAIT_DISCARD",
  "seat": 2,
  "hand": [0,1,2,9,10],
  "melds": [
    {"type":"PUNG","tiles":[18,18,18],"claimedFrom":1,"claimedTile":18}
  ],
  "flowers": [34],
  "opponents": [
    {"seat":0,"handCount":13,"melds":[],"discards":[4,5],"flowerCount":0,
     "score":100,"seatWind":0}
  ],
  "wallRemaining": 42,
  "dealer": 0,
  "roundWind": 0,
  "seatWind": 2,
  "lastDiscard": {"seat":0,"tile":4,"turn":11},
  "legal": {
    "actions": ["discard","peng","concealed_kong","added_kong","hu"],
    "discardable": [0,1,2,9,10]
  },
  "window": null
}
```

裁剪规则（硬约束）：

- 只下发自己的 `hand`；别家只给 `handCount`（含刚摸的牌，不泄露牌面）。
- 别家 `melds` 只下发已亮出的副露；暗杠下发 `{"type":"KONG_CONCEALED","tiles":[]}` 并给 `concealed:true`，不泄露牌值。
- `wallRemaining` 只给数量，不给顺序。
- `legal` 只描述**本座位**当前合法动作；`window` 只出现在有响应权的座位上（其他人是 `null`）。

### 4.3 `window`（响应窗口）

```json
{
  "kind": "discard_claim",
  "from": 0,
  "tile": 4,
  "deadline": 1762473609200,
  "remainingMs": 8000,
  "options": [
    {"action":"hu"},
    {"action":"peng"},
    {"action":"chi","choices":[[2,3],[3,5]]}
  ]
}
```

- 出牌后服务端计算所有可响应座位，只给这些座位发 `window`。
- 同一张牌多家可和时：`ruleSet` 允许一炮多响则全部受理，否则按 **出牌者下家起的逆时针顺序**取第一个（座位号递增取模 4）。
- 优先级：**胡 > 杠/碰 > 吃**。高优先级受理后，低优先级窗口自动作废。
- 超时未响应 = `pass`。
- 抢杠和窗口 `kind = "rob_kong"`，仅针对**加杠**（暗杠不可抢）。

### 4.4 `event`

事件体按座位裁剪；公共事件全员一致。

```json
{"type":"discard","seat":0,"tile":4,"turn":12}
{"type":"draw","seat":2,"tile":9}          // 仅摸牌者收到 tile
{"type":"draw","seat":2}                    // 其他人只收到「有人摸牌」
{"type":"peng","seat":1,"tile":4,"from":0,"handAfterCount":10}
{"type":"kong","seat":1,"tile":4,"kind":"exposed","from":0}
{"type":"hu","seat":2,"by":"self_draw","tile":9}
{"type":"draw_game","reason":"wall_empty"}
```

### 4.5 `action_ack`

```json
{"sid":"a1b2c3d4","ok":true}
{"sid":"a1b2c3d5","ok":false,"code":"E_ILLEGAL_ACTION","message":"现在不能碰"}
```

受理成功的动作随后必有 `event` + `state`（或 `game_over`）。

### 4.6 `game_over`

```json
{
  "gameId": "g-20261107-7f3a",
  "draw": false,
  "reason": "self_draw",
  "winners": [
    {
      "seat": 2,
      "from": null,
      "selfDraw": true,
      "winningTile": 9,
      "fan": [{"id":"PING_HU","name":"平和","fan":2,"count":1}],
      "totalFan": 12,
      "points": 12,
      "hand": [0,1,2,9,10,18,18,18,27,28,31,4,5,6]
    }
  ],
  "deltaScores": [-12,-12,36,-12],
  "scores": [88,88,136,88],
  "dealer": 0,
  "roundWind": 0,
  "dealerContinues": false
}
```

- `fan` / `totalFan` / `points` 全部来自服务端复算（core `ScoreEngine`），客户端上报值一律忽略。
- `deltaScores` 为服务端计算的座次增减，四项之和为 0。

---

## 5. 动作意图（C→S `action`）

统一消息类型 `action`，`p.action` 区分：

| `action` | 负载 | 合法时机 |
|---|---|---|
| `discard` | `{tile}` | 轮到自己且 `phase=AWAIT_DISCARD`，必须是手牌中的牌 |
| `chi` | `{tiles:[a,b], claimed}` | 吃牌窗口，`tiles` 为自己手牌中的两张 |
| `peng` | `{tile}` | 碰牌窗口，`tile` = 被碰的牌 |
| `gang` | `{tile, kind:"exposed"\|"concealed"\|"added"}` | 明杠在窗口；暗/加杠在自己回合 |
| `hu` | `{}` | 响应窗口（点炮）或自己回合（自摸） |
| `pass` | `{}` | 响应窗口内放弃 |

服务端校验：座位归属 → 阶段 → 手牌是否包含 → 动作是否在 `legal` 集合 → 规则细项（国标 8 番起和等）。任一失败返回 `action_ack ok=false` 且**不推进**任何状态。

---

## 6. 响应窗口与回合推进

```
出牌 A
  └─ 计算可响应集合（胡/杠/碰/吃）
       ├─ 空 → 直接轮到下家摸牌
       └─ 非空 → 广播 discard 事件 → 给可响应者发 window（deadline=8s）
            ├─ 收到 hu（最高优先级）→ 结算
            ├─ 收到 gang/peng → 形成副露 → 该玩家出牌
            ├─ 收到 chi → 形成副露 → 该玩家出牌
            ├─ 全部 pass / 超时 → 按原顺序下家摸牌
            └─ 高优先级受理 → 低优先级窗口作废，不再等待
```

流局条件：牌墙摸空（`wallRemaining = 0`）时当前摸牌者无法摸牌 → `draw_game`，`reason="wall_empty"`。

---

## 7. 断线重连与托管

### 7.1 判定与时间参数（均可用环境变量覆盖）

| 参数 | 默认 | 含义 |
|---|---|---|
| `MAHJONG_HEARTBEAT_TIMEOUT_MS` | 15000 | 超过该时间无任何消息 → 连接死亡、座位标记离线 |
| `MAHJONG_AUTOPLAY_DELAY_MS` | 3000 | 离线后多久由 AI 接管该座位 |
| `MAHJONG_RESUME_GRACE_MS` | 180000 | `resumeToken` 有效期；期间随时可重连回原座位 |

### 7.2 规则（写清楚，避免歧义）

1. **连接死亡不释放座位。** 只把座位标记 `online=false`，广播 `seat_online`。
2. **离线 3 秒后 AI 接管。** 托管 AI 固定用**第二级**（普通：向听/牌效 + 基础防守，不碾压）。接管期间该座位照常摸打、响应、和牌。
3. **宽限期内重连。** 客户端发 `reconnect {room, resumeToken}`：
   - 令牌匹配且未过期 → 旧连接若还在，服务端踢掉旧连接（`E_SEAT_TAKEOVER`）；新连接立即取消托管，下发 `sync`（含 `state` + 当前 `window` 剩余时间 + `room_state`），随后补发一次 `state` 与未过期的 `window`，保证「刚好轮到自己」时能继续行动。
   - 令牌不匹配/过期/房间已销毁 → `E_RESUME_EXPIRED`。
4. **宽限期到（180 秒）。** `resumeToken` 作废（此后无法再重连）；座位保持 **AI 托管直到本局结束**，由托管 AI 打完整局并正常参与结算。
   - 等待阶段（未开局）到期：直接释放座位。
   - 对局中到期：本局结束后释放该座位，房间回到等待状态，其他人可继续等新玩家或下一局。
5. **对局中主动 `room_leave`** = 认输：该座位立即转为 AI 打完整局，牌谱记 `resigned`，本局结束后释放座位。等待阶段离开则立即空出座位。
6. **房主长时间离线** 不影响对局；下一局开始前房主权限不移交（v1 简化，超时不开局即可）。
7. 服务端重启会丢失内存房间（v1 不持久化热状态）；牌谱已落库不受影响。

### 7.3 `reconnect` / `sync`

```json
{"v":1,"t":"reconnect","sid":"r1","cseq":3,"room":"R7K2M9","seat":0,
 "p":{"resumeToken":"rt-...","lastSseq":120}}
```

`sync.p`：

```json
{
  "serverTime": 1762473600000,
  "room": { /* room_state.p */ },
  "state": { /* 该座位的裁剪 state；对局中才有 */ },
  "window": { /* 若该座位正在响应窗口内 */ },
  "lastSseq": 128,
  "autoPlaying": false
}
```

---

## 8. 牌谱与战绩

### 8.1 存储模型

一次对局 = `规则配置 + seed + 已接受动作序列`。牌墙由 `seed` 决定，动作序列决定走向，二者即可复现整局（含番型与得分）。

```json
{
  "gameId": "g-20261107-7f3a",
  "room": "R7K2M9",
  "ruleSet": "MCR",
  "seed": "9f1c2a7e5b",
  "dealer": 0,
  "roundWind": 0,
  "dealerStreak": 0,
  "initialScores": [100, 100, 100, 100],
  "seats": [
    {"seat":0,"kind":"human","playerId":"p-ab12","nickname":"阿东"},
    {"seat":1,"kind":"ai","aiLevel":2,"nickname":"AI·普通"}
  ],
  "actions": [
    {"seat":0,"turn":1,"action":"discard","tile":4},
    {"seat":2,"turn":2,"action":"peng","tile":4},
    {"seat":2,"turn":2,"action":"discard","tile":9},
    {"seat":1,"turn":3,"action":"gang","tile":4,"kind":"concealed"}
  ],
  "result": { /* game_over.p */ },
  "createdAt": 1762473600000
}
```

复现方式（服务端同款，见 `tools/SimCommon.kt` 的 `replayMismatch`）：

```kotlin
val game = MahjongGame(config, seed, record.dealer, record.roundWind, record.dealerStreak, record.initialScores)
record.actions.forEach { game.apply(it.toAction()) }
check(game.gameResult!!.deltaScores == record.result.deltaScores)
```

### 8.2 数据库

| 部署 | 启用方式 |
|---|---|
| SQLite（默认） | `MAHJONG_DB=./data/mahjong.db` |
| Postgres（部署选项） | `MAHJONG_DB_URL=jdbc:postgresql://host:5432/mahjong` + `MAHJONG_DB_USER` / `MAHJONG_DB_PASSWORD` |
| 内存（测试） | `MAHJONG_DB=:memory:` |

表结构（两种库通用）：

```sql
CREATE TABLE IF NOT EXISTS replays (
  game_id      VARCHAR(64) PRIMARY KEY,
  room_code    VARCHAR(16) NOT NULL,
  rule_set     VARCHAR(16) NOT NULL,
  seed         VARCHAR(32) NOT NULL,
  dealer       INT NOT NULL,
  round_wind   INT NOT NULL DEFAULT 0,
  dealer_streak INT NOT NULL DEFAULT 0,
  scores_json  TEXT NOT NULL DEFAULT '[]',   -- 本局开始前四家分数
  seats_json   TEXT NOT NULL,
  actions_json TEXT NOT NULL,
  result_json  TEXT NOT NULL,
  created_at   BIGINT NOT NULL
);
```

写入时机：`game_over` 生成后异步写库，失败只记日志，不影响对局。查询接口 v1 仅提供内部 API（后续做战绩页时再加 HTTP 路由）。

---

## 9. 反作弊底线

1. 发牌/牌墙/骰子全部服务端生成；`seed` 在开局时公开仅用于复盘校验，不回传牌墙。
2. 客户端只发意图。服务端校验后才会改变状态；非法动作不产生任何副作用。
3. 番型与得分由服务端 `ScoreEngine` 复算，`game_over` 中的分数由服务端生成，客户端无上报入口。
4. 私有视图裁剪（§4.2）确保客户端拿不到别家手牌与牌墙顺序。
5. 连接级 `cseq` 去重，防重放；`resumeToken` 一次性绑定座位，重连后旧连接被踢。

---

## 10. 错误码

| 码 | 含义 |
|---|---|
| `E_BAD_VERSION` | 协议版本不匹配 |
| `E_BAD_JSON` | 负载无法解析 |
| `E_UNAUTHENTICATED` | 未 `hello` |
| `E_ALREADY_AUTHED` | 重复 `hello` |
| `E_ROOM_NOT_FOUND` | 房间不存在 |
| `E_ROOM_FULL` | 无空座位 |
| `E_BAD_PASSWORD` | 房间密码错误 |
| `E_NOT_IN_ROOM` | 不在房间内 |
| `E_NOT_HOST` | 非房主操作 |
| `E_NOT_ENOUGH_SEATS` | 开局座位不足 |
| `E_NOT_ALL_READY` | 有真人未准备 |
| `E_ALREADY_PLAYING` | 对局进行中 |
| `E_NOT_PLAYING` | 未在对局中 |
| `E_ILLEGAL_ACTION` | 动作在当前阶段不合法 |
| `E_NOT_YOUR_TURN` | 未轮到你 |
| `E_TILE_NOT_IN_HAND` | 手牌中没有该牌 |
| `E_SEAT_TAKEOVER` | 同座位在其他连接重连，本连接被顶替 |
| `E_RESUME_EXPIRED` | 重连令牌失效 |
| `E_RATE_LIMITED` | 消息频率超限 |
| `E_INTERNAL` | 服务端内部错误 |

错误统一走 `error` 消息（或带 `sid` 的 `action_ack`）：

```json
{"v":1,"t":"error","sid":"a1b2c3d4","sseq":129,"room":"R7K2M9","seat":0,
 "p":{"code":"E_NOT_YOUR_TURN","message":"未轮到你"}}
```

---

## 11. 运行与验证

```powershell
# 1) 启动服务端（默认 0.0.0.0:8080）
cd g:\AI\麻将\server
.\gradlew.bat run

# 2) 健康检查
curl http://127.0.0.1:8080/health
# => {"status":"ok","rooms":0,"protocol":1}

# 3) 端到端：4 个 WebSocket 客户端自动打完一局（国标）
.\gradlew.bat simulateFour

# 3b) 换成广东鸡平胡（会出现真实和牌与番数结算）
.\gradlew.bat simulateFour -Prule=GD_JIPINGHU

# 4) 断线重连 + 托管验证
.\gradlew.bat simulateResume

# 5) 局域网自动发现 + 房主踢人边界验证（自带 UDP 探针）
.\gradlew.bat simulateLobby

# 可选：改发现端口（默认 45231）
.\gradlew.bat simulateLobby -PdiscoveryPort=45299
```

环境变量：`MAHJONG_DISCOVERY_PORT`（默认 45231）、`MAHJONG_DISCOVERY=off`（关闭发现）。

`simulateFour` 在进程内起一个随机端口的服务端，用 4 条真实 WS 连接完成：创建房间 → 加入 → 准备 → 开局 → 逐手出牌/响应 → 结算，并断言：

- 每个客户端只收到自己的 `hand`，对手只有 `handCount`，别家暗杠 `tiles` 为空；
- 公共广播 `sseq` 单调不回退；
- 牌谱落库，且用 `seed + 动作序列` 重放能得到完全相同的 `deltaScores`。

`simulateResume` 覆盖：对局中断线 → `seat_online(offline)` 广播 → 约 0.6s 后 AI（固定二级）代打并产生真实出牌事件 → 宽限期内带 `resumeToken` 重连 → `autoPlaying=false` 的 `sync` 快照 → 继续打完并复现牌谱。

`simulateLobby` 覆盖（真实 WS + 真实 UDP）：

- 建房后 UDP `MJ?1` 查询立即返回该房间，`wsPort` 与摘要人数/房主/密码标记正确；
- 关闭的房间从发现列表消失；
- 密码房从发现结果点入 + 正确密码可直接加入；
- 非房主踢人 `E_NOT_HOST`、踢自己 `E_CANNOT_KICK_SELF`、踢 AI `E_NOT_A_PLAYER`；
- 踢真人：被踢方收到 `room_kicked` 与原因后连接被关闭，座位释放，新玩家可立即加入；
- 重复踢空座位幂等成功；踢已断线玩家成功并释放座位；
- 房主退出后房主权限转移，最后一人退出房间自动关闭；
- 对局中踢人（直接驱动 `Room`）：座位保留但标记离线、旧 `resumeToken` 失效、牌局不中断、重复踢幂等、房主退出转移房主、终局后释放座位。

本机实测（Windows / JDK 17）：

| 命令 | 结果 |
|---|---|
| `simulateFour`（MCR） | PASS，103 手流局，牌谱 103 条动作复现一致 |
| `simulateFour -Prule=GD_JIPINGHU` | PASS，座位 2 点炮和牌，1 番，`deltaScores=[-2,0,2,0]`，32 条动作复现一致 |
| `simulateResume`（MCR） | PASS，turn 13 断线，AI 托管 0.66s 接管，宽限期内重连回到座位 1，最终 82 手结束，83 条动作复现一致 |
| `simulateLobby` | PASS，UDP 自动发现与踢人全部检查通过（含重复踢/离线踢/对局中踢/房主转移） |
| `:app:compileDebugKotlinAndroid` | BUILD SUCCESSFUL（Android 客户端含发现/踢人 UI） |

---

## 12. 未完成项（v1 之后）

1. `Matchmaker` 只有接口与内存占位，未做匹配队列与段位。
2. 观战、聊天/表情、投降（本局主动认输已按 AI 接管处理）、举报未实现。
3. 房间热状态只在内存，服务重启后不恢复进行中的对局（牌谱已落库）。
4. 无账号/JWT；连接级限流已实现（单连接 40 条/秒），生产部署仍需反向代理补 TLS 与全局限流。
5. 出牌回合计时器未开启（目前只有响应窗口超时）；后期补「超时自动打出摸到的牌」。
