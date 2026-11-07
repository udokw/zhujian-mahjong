package mahjong.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import mahjong.core.ai.AiBot
import mahjong.core.ai.AiLevel
import mahjong.core.api.GameSession
import mahjong.core.rules.RuleConfig

class UiApiTest {

    @Test
    fun snapshotExposesOnlyPublicHandInfo() {
        val session = GameSession(
            config = RuleConfig.guangdongJipinghu().copy(buyHorseEnabled = false),
            seed = 77L,
        )
        val snap = session.snapshot
        assertEquals(4, snap.players.size)
        // 公开快照只有手牌张数；完整手牌走 viewFor。
        assertEquals(session.viewFor(0).hand.size, snap.players[0].handCount)
        assertTrue(snap.players.all { it.handCount in 13..14 })
        assertTrue(session.actor != null)
        assertEquals(snap, session.snapshot)
    }

    @Test
    fun actionStreamReplaysToSameResult() {
        val config = RuleConfig.guangdongTuidaohu().copy(buyHorseEnabled = false)
        val session = GameSession(config, seed = 99L)
        val bots = (0..3).map { AiBot(AiLevel.NORMAL, 99L + it) }

        var guard = 0
        while (!session.isOver && guard++ < 3000) {
            val seat = session.actor ?: break
            val legal = session.legalActions(seat)
            if (legal.isEmpty()) break
            session.submit(bots[seat].choose(session.viewFor(seat), legal))
        }

        val result = assertNotNull(session.result)
        val logged = session.actionLog()
        assertTrue(logged.isNotEmpty())

        val replay = session.toReplay()
        var replayedSteps = 0
        val replayed = replay.replay { replayedSteps++ }

        val replayResult = assertNotNull(replayed.result)
        assertEquals(result.deltaScores, replayResult.deltaScores)
        assertEquals(result.reason, replayResult.reason)
        assertEquals(result.winners.size, replayResult.winners.size)
        assertEquals(logged.size, replay.actions.size)
        assertEquals(logged.size, replayedSteps)
    }
}
