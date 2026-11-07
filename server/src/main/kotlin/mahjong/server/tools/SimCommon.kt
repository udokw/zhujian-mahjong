package mahjong.server.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mahjong.core.analysis.HandAnalyzer
import mahjong.core.engine.MahjongGame
import mahjong.core.model.Action
import mahjong.core.rules.RuleConfig
import mahjong.core.rules.RulePreset
import mahjong.server.persist.ReplayRecord
import mahjong.server.protocol.ActionBody
import mahjong.server.protocol.Envelope
import mahjong.server.protocol.StateView
import mahjong.server.protocol.WindowView
import mahjong.server.protocol.Wire

// ---------------------------------------------------------------- 小工具

fun body(vararg pairs: Pair<String, JsonElement>): JsonObject =
    buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

fun obj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((key, value) in pairs) {
        when (value) {
            null -> Unit
            is JsonElement -> put(key, value)
            is String -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            is Boolean -> put(key, value)
            else -> put(key, value.toString())
        }
    }
}

inline fun <reified T> jsonOf(value: T): JsonObject =
    Wire.json.encodeToJsonElement(value).jsonObject

fun JsonObject.str(key: String): String? = (this[key] as? JsonElement)?.jsonPrimitive?.content

fun JsonObject.int(key: String): Int? = runCatching { this[key]!!.jsonPrimitive.int }.getOrNull()

fun JsonObject.bool(key: String): Boolean? = runCatching { this[key]!!.jsonPrimitive.content.toBoolean() }.getOrNull()

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

// ---------------------------------------------------------------- 对局循环

/**
 * 一个模拟玩家的完整行为：收到 state 就在自己回合出牌，收到 window 就胡/过。
 * 同时做隐私与序号校验。
 */
suspend fun SimAgent.playLoop(
    done: CompletableDeferred<Envelope>,
    violations: MutableList<String>,
    onTurn: ((Int) -> Unit)? = null,
    onEnvelope: ((Envelope) -> Unit)? = null,
) {
    var lastSseq = 0L
    while (!done.isCompleted && currentCoroutineContext().isActive) {
        val envelope = next(30) ?: continue
        onEnvelope?.invoke(envelope)
        if (envelope.sseq > 0) {
            if (envelope.sseq < lastSseq) {
                violations.add("$name: sseq 回退 ${lastSseq} -> ${envelope.sseq}")
            }
            lastSseq = maxOf(lastSseq, envelope.sseq)
        }
        when (envelope.t) {
            "state" -> {
                verifyState(this, envelope, violations)
                val view = runCatching { Wire.payload<StateView>(envelope) }.getOrNull() ?: continue
                onTurn?.invoke(view.turn)
                actOnState(view)
            }

            "window" -> {
                val window = runCatching { Wire.payload<WindowView>(envelope) }.getOrNull() ?: continue
                answerWindow(window)
            }

            "event" -> verifyEvent(this, envelope, violations)

            "action_ack" -> {
                val ok = envelope.p.bool("ok") ?: false
                if (!ok) {
                    violations.add("$name: 动作被拒绝 ${envelope.p.str("code")} ${envelope.p.str("message")}")
                    lastActedTurn = -1
                }
            }

            "game_over" -> done.complete(envelope)

            "error" -> violations.add("$name: 服务端错误 ${envelope.p.str("code")} ${envelope.p.str("message")}")

            "room_closed" -> violations.add("$name: 房间被关闭 ${envelope.p.str("reason")}")
        }
    }
}

private fun SimAgent.actOnState(view: StateView) {
    if (view.currentPlayer != view.seat) return
    val legal = view.legal.actions
    if ("discard" !in legal && "hu" !in legal) return
    if (view.turn == lastActedTurn) return

    if ("hu" in legal) {
        lastActedTurn = view.turn
        send("action", jsonOf(ActionBody("hu")))
        return
    }
    val tile = chooseDiscard(view) ?: return
    lastActedTurn = view.turn
    send("action", jsonOf(ActionBody("discard", tile = tile)))
}

/** 用 core 的向听计算挑一张最不亏的牌，保证模拟对局能真正推进到和牌。 */
private fun SimAgent.chooseDiscard(view: StateView): Int? {
    val candidates = view.legal.discardable.ifEmpty { view.hand.distinct() }
    if (candidates.isEmpty()) return null
    val meldCount = view.melds.size
    return candidates.minByOrNull { tile ->
        val remaining = view.hand.toMutableList().apply { remove(tile) }
        HandAnalyzer.shanten(remaining, meldCount)
    }
}

private fun SimAgent.answerWindow(window: WindowView) {
    val key = "${window.kind}:${window.from}:${window.tile}:${window.deadline}"
    if (key == lastWindowKey) return
    lastWindowKey = key
    val canHu = window.options.any { it.action == "hu" }
    send("action", jsonOf(ActionBody(if (canHu) "hu" else "pass")))
}

// ---------------------------------------------------------------- 校验

fun verifyState(agent: SimAgent, envelope: Envelope, violations: MutableList<String>) {
    if (envelope.seat != agent.seat) {
        violations.add("${agent.name}: state.seat=${envelope.seat} 与本地座位 ${agent.seat} 不一致")
    }
    val view = runCatching { Wire.payload<StateView>(envelope) }.getOrNull() ?: run {
        violations.add("${agent.name}: state 负载无法解析")
        return
    }
    if (view.hand.size > 14) violations.add("${agent.name}: 自己手牌数异常 ${view.hand.size}")
    if (view.opponents.size != 3) violations.add("${agent.name}: 对手数量 ${view.opponents.size} != 3")

    val raw = envelope.p.arr("opponents") ?: return
    for (element in raw) {
        val opponent = element.jsonObject
        if (opponent.containsKey("hand")) {
            violations.add("${agent.name}: 收到了对手手牌字段（应只给 handCount）")
        }
        val handCount = opponent.int("handCount") ?: -1
        if (handCount !in 0..14) {
            violations.add("${agent.name}: 对手 handCount 越界 $handCount")
        }
        for (meld in opponent.arr("melds") ?: JsonArray(emptyList())) {
            val m = meld.jsonObject
            if (m.bool("concealed") == true && (m.arr("tiles")?.size ?: 0) > 0) {
                violations.add("${agent.name}: 别家暗杠牌面泄露")
            }
        }
    }

    // 自己看到的牌总量不能超过整副牌。
    val total = view.hand.size + view.melds.sumOf { it.tiles.size } +
        view.flowers.size +
        view.opponents.sumOf { it.melds.sumOf { m -> m.tiles.size } + it.discards.size + it.flowerCount }
    if (total > 144) violations.add("${agent.name}: 可见牌总数 $total 超过 144")
}

fun verifyEvent(agent: SimAgent, envelope: Envelope, violations: MutableList<String>) {
    val type = envelope.p.str("type") ?: return
    val seat = envelope.p.int("seat") ?: -1
    if (type == "draw" && seat != agent.seat && envelope.p["tile"] != null) {
        violations.add("${agent.name}: 别家摸牌牌面泄露 ${envelope.p["tile"]}")
    }
    if (type == "kong" && envelope.p.str("kind") == "concealed" && seat != agent.seat &&
        envelope.p["tile"] != null
    ) {
        violations.add("${agent.name}: 别家暗杠牌面泄露 ${envelope.p["tile"]}")
    }
}

// ---------------------------------------------------------------- 牌谱复现

/**
 * 用 seed + 动作序列重放，比较结果是否与牌谱一致。
 * 返回 null 表示一致，否则返回差异描述。
 */
fun replayMismatch(record: ReplayRecord): String? {
    val config = when (record.ruleSet) {
        "MCR" -> RuleConfig.of(RulePreset.MCR_1998)
        "GD_TUIDAOHU" -> RuleConfig.of(RulePreset.GD_TUIDAOHU)
        "GD_JIPINGHU" -> RuleConfig.of(RulePreset.GD_JIPINGHU)
        else -> return "未知规则集 ${record.ruleSet}"
    }
    val seed = runCatching { java.lang.Long.parseUnsignedLong(record.seed, 16) }
        .getOrElse { return "seed 无法解析: ${record.seed}" }
    val game = MahjongGame(
        config = config,
        seed = seed,
        dealer = record.dealer,
        roundWind = record.roundWind,
        dealerStreak = record.dealerStreak,
        initialScores = record.initialScores.takeIf { it.size == 4 },
    )
    for ((index, action) in record.actions.withIndex()) {
        val mapped = action.toAction() ?: return "第 $index 条动作无法映射: $action"
        val legal = game.legalActions(action.seat)
        if (legal.none { it == mapped }) {
            return "第 $index 条动作 $action 在重放时非法"
        }
        game.apply(mapped)
    }
    val result = game.gameResult ?: return "重放后牌局未结束"
    val expected = Wire.json.decodeFromString(
        mahjong.server.protocol.GameOverView.serializer(),
        record.resultJson,
    )
    if (expected.draw != result.draw) return "和牌/流局不一致"
    if (expected.deltaScores != result.deltaScores) {
        return "分数不一致 记录=${expected.deltaScores} 复现=${result.deltaScores}"
    }
    val expectedWinners = expected.winners.map { it.seat }.sorted()
    val actualWinners = result.winners.map { it.winner }.sorted()
    if (expectedWinners != actualWinners) return "赢家不一致 记录=$expectedWinners 复现=$actualWinners"
    return null
}

private fun mahjong.server.game.RecordedAction.toAction(): Action? = when (action) {
    "discard" -> tile?.let { Action.Discard(seat, it) }
    "chi" -> claimed?.let { Action.Chi(seat, tiles, it) }
    "peng" -> tile?.let { Action.Peng(seat, it) }
    "gang" -> tile?.let {
        when (kind) {
            "concealed" -> Action.ConcealedKong(seat, it)
            "added" -> Action.AddedKong(seat, it)
            else -> Action.ExposedKong(seat, it)
        }
    }

    "hu" -> Action.Hu(seat)
    "pass" -> Action.Pass(seat)
    else -> null
}
