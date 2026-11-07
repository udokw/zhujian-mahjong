# GameSession 界面接入说明

`mahjong.core.api.GameSession` 是 Compose 界面唯一需要持有的入口，位于 `core/src/commonMain/kotlin/mahjong/core/api/GameSession.kt`。类型签名视为稳定接口，后续只增不改。

## 1. 初始化

```kotlin
val session = GameSession(
    config = RuleConfig.of(RulePreset.GD_JIPINGHU),
    seed = seed,
    dealer = dealer,
    roundWind = roundWind,
    dealerStreak = dealerStreak,
    initialScores = scores,
)
val openingEvents = session.drainEvents()   // 发牌、补花等开局事件
```

`drainEvents()` 只在开局调用即可；之后每次 `submit` 的返回值就是本步事件。

## 2. 取快照的时机

- 渲染桌面：读 `session.snapshot`（`GameSnapshot`），只含各家手牌张数、副露、弃牌、花牌、分数、牌墙余量等公开信息，不含任何手牌。
- 渲染自己的手牌：读 `session.viewFor(localSeat)`（`PlayerView`），这是唯一能看到自己手牌的入口。
- 刷新时机：初始化后一次、每次 `submit` 返回后一次、每次重放回调后一次。快照是不可变值，不要跨 `submit` 缓存旧快照。
- 结算：`session.result` 或 `snapshot.result`；结束后 `actor == null`，`legalActions` 返回空。

## 3. 合法动作怎么取

没有推送式订阅，按“当前行动座位”拉取即可：

```kotlin
val seat = session.actor            // null 表示无人需要操作
val actions = seat?.let { session.legalActions(it) } ?: emptyList()
```

- `actor` 变化（玩家操作、AI 行动、摸牌、响应窗口开启）后重新拉一次。
- 按钮只从 `actions` 生成，不要自己拼 `Action`；`actions` 为空时禁用操作区。
- 响应窗口（吃碰杠胡、抢杠）由 `snapshot.pendingClaimSeats` / `pendingRobKongSeats` 标识，逐个座位提交响应，全部响应后引擎自动结算。
- 引擎不做本地乐观更新：先 `submit`，再用返回事件和最新快照更新界面。

## 4. 事件与动画

`submit(action)` 返回 `List<GameEvent>`，按顺序播放即可；引擎内部保证同一动作产生的事件相邻。

```kotlin
val events = session.submit(action)
events.forEach { animator.enqueue(it) }
```

需要“动作 + 事件”成对回放时读 `session.actionLog(): List<ActionStep>`，每步含 `turn`、`action`、`events`。建议动画队列清空后再允许下一次操作，避免视觉状态跳过。

## 5. 重放 / 观战

```kotlin
val replay: GameReplay = session.toReplay()   // 可序列化保存
replay.replay { step: ActionStep ->
    // 每步回调一次，用 step.events 驱动动画
}
```

`GameReplay` 保存了规则、种子、庄家、圈风、连庄数与完整动作流；同一记录重放必然得到同一结果。

## 6. AI 座位

非真人座位用 `AiBot(level, seed).choose(view, legalActions)`，与真人共用同一个 `submit` 流程。AI 回合可以同步连续推进，但每步都要先取最新 `viewFor` 与 `legalActions`。

## 7. 线程与错误处理

- `GameSession` / `MahjongGame` 非线程安全，固定在一个 dispatcher（或主线程）串行调用。
- 非法动作会抛 `IllegalArgumentException`，不会静默忽略，也不会推进牌局。联机时应把远端动作交给服务端同一引擎校验；客户端不要吞掉异常后继续用旧快照。
- 联机同步只需要传 `Action`（含 `player`）；收到动作后服务端 `submit`，再把事件流和快照广播给客户端。
