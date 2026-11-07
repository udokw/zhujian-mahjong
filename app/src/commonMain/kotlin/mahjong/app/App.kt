package mahjong.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mahjong.app.audio.GameAudio
import mahjong.app.audio.SoundEffect
import mahjong.app.audio.platformInitAudio
import mahjong.app.game.LocalGameSession
import mahjong.app.game.OnlineGameSession
import mahjong.app.game.SettlementUi
import mahjong.app.screens.LobbyScreen
import mahjong.app.screens.OnlineScreen
import mahjong.app.screens.RecordsScreen
import mahjong.app.screens.SettingsScreen
import mahjong.app.screens.SettlementScreen
import mahjong.app.screens.SplashScreen
import mahjong.app.screens.TableScreen
import mahjong.app.settings.AppSettings
import mahjong.app.settings.Arenas
import mahjong.app.settings.SettingsRepository
import mahjong.app.settings.TileScale
import mahjong.app.theme.LocalMjColors
import mahjong.app.theme.LocalMjType
import mahjong.app.theme.MahjongTheme
import mahjong.app.theme.MjRadius
import mahjong.app.ui.MjText
import mahjong.core.rules.GameMode
import mahjong.core.rules.RuleConfig

private enum class Screen { SPLASH, LOBBY, TABLE, SETTLEMENT, SETTINGS, RECORDS, ONLINE }

private data class GameMeta(
    val handNumber: Int = 1,
    val dealer: Int = 0,
    val roundWind: Int = 0,
    val dealerStreak: Int = 0,
    val scores: List<Int>? = null,
)

@Composable
fun App() {
    var settings by remember { mutableStateOf(SettingsRepository.load()) }
    var screen by remember { mutableStateOf(Screen.SPLASH) }
    var settingsReturn by remember { mutableStateOf(Screen.LOBBY) }
    var session by remember { mutableStateOf<LocalGameSession?>(null) }
    var onlineSession by remember { mutableStateOf<OnlineGameSession?>(null) }
    var settlement by remember { mutableStateOf<SettlementUi?>(null) }
    var settlementOnline by remember { mutableStateOf(false) }
    var meta by remember { mutableStateOf(GameMeta()) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun update(next: AppSettings) {
        settings = next
        SettingsRepository.save(next)
    }

    fun showToast(message: String) {
        toast = message
        scope.launch {
            delay(1_800)
            if (toast == message) toast = null
        }
    }

    fun startLocal(metaSnapshot: GameMeta) {
        val arena = Arenas[settings.arenaIndex.coerceIn(0, Arenas.lastIndex)]
        val base = settings.ruleConfig()
        val config: RuleConfig = base.copy(
            baseScore = arena.baseScore,
            mcrBaseScore = if (base.mode == GameMode.MCR) arena.baseScore else base.mcrBaseScore,
        )
        val names = listOf(
            settings.nickname.ifBlank { "我" },
            "青禾",
            "竹隐",
            "南舟",
        )
        session?.cancel()
        val next = LocalGameSession(
            config = config,
            level = settings.aiLevel,
            meSeat = 0,
            names = names,
            handNumber = metaSnapshot.handNumber,
            initialScores = metaSnapshot.scores,
            dealer = metaSnapshot.dealer,
            roundWind = metaSnapshot.roundWind,
            dealerStreak = metaSnapshot.dealerStreak,
            scope = scope,
            settings = { settings },
            onFinished = { ui ->
                settlement = ui
                screen = Screen.SETTLEMENT
            },
        )
        session = next
        next.start()
        DebugScenario.consume()?.let { next.debugInject(it) }
        screen = Screen.TABLE
    }

    fun playAgain() {
        val ui = settlement ?: return
        val config = session?.config ?: settings.ruleConfig()
        val previous = meta.scores ?: List(4) { config.startingScore }
        val nextScores = previous.mapIndexed { index, score ->
            score + ui.deltas.getOrElse(index) { 0 }
        }
        val dealerWon = ui.winners.any { it.seat == meta.dealer }
        val continues = if (ui.draw) config.dealerContinuesOnDraw else dealerWon && config.dealerContinuesOnWin
        val nextDealer = if (continues) meta.dealer else (meta.dealer + 1) % 4
        val nextWind = if (!continues && nextDealer == 0) (meta.roundWind + 1) % 4 else meta.roundWind
        val next = GameMeta(
            handNumber = meta.handNumber + 1,
            dealer = nextDealer,
            roundWind = nextWind,
            dealerStreak = if (continues) meta.dealerStreak + 1 else 0,
            scores = nextScores,
        )
        meta = next
        settlement = null
        settlementOnline = false
        startLocal(next)
    }

    fun ensureOnlineSession(): OnlineGameSession {
        onlineSession?.let { return it }
        val created = OnlineGameSession(
            scope = scope,
            settings = { settings },
            onSettlement = { ui ->
                settlementOnline = true
                settlement = ui
                screen = Screen.SETTLEMENT
            },
            onClosure = { reason ->
                showToast(reason)
                onlineSession?.cancel()
                onlineSession = null
                settlement = null
                settlementOnline = false
                screen = Screen.LOBBY
            },
            onMessage = { showToast(it) },
        )
        onlineSession = created
        return created
    }

    fun leaveOnlineToLobby() {
        onlineSession?.leave()
        onlineSession = null
        settlement = null
        settlementOnline = false
        meta = GameMeta()
        screen = Screen.LOBBY
    }

    fun backToLobby() {
        session?.cancel()
        session = null
        settlement = null
        meta = GameMeta()
        screen = Screen.LOBBY
    }

    LaunchedEffect(Unit) {
        // 在启动页期间预加载敲桌音效与 34 种牌名语音，进入牌桌即可用。
        platformInitAudio()
        delay(1_350)
        if (screen == Screen.SPLASH) screen = Screen.LOBBY
    }

    // 设置里的音效开关/音量直接同步给播放层，UI 按钮无需再逐处传参。
    LaunchedEffect(settings.sound, settings.volume) {
        GameAudio.configure(settings.sound, settings.volume)
    }

    LaunchedEffect(screen, onlineSession?.table) {
        if (screen == Screen.ONLINE && onlineSession?.table != null) {
            screen = Screen.TABLE
        }
    }

    MahjongTheme(
        mode = settings.theme,
        skin = settings.skin,
        reduceMotion = settings.reduceMotion,
    ) {
        val colors = LocalMjColors.current
        val type = LocalMjType.current
        Box(Modifier.fillMaxSize().background(colors.bg)) {
            when (screen) {
                Screen.SPLASH -> SplashScreen()

                Screen.LOBBY -> LobbyScreen(
                    settings = settings,
                    onChange = { update(it) },
                    onQuickStart = {
                        val fresh = GameMeta()
                        meta = fresh
                        startLocal(fresh)
                    },
                    onFriendRoom = {
                        ensureOnlineSession()
                        screen = Screen.ONLINE
                    },
                    onSettings = {
                        settingsReturn = Screen.LOBBY
                        screen = Screen.SETTINGS
                    },
                    onRecords = { screen = Screen.RECORDS },
                    onResumeTable = { if (session?.table != null) screen = Screen.TABLE },
                    inGame = session?.table != null,
                    onToast = { showToast(it) },
                )

                Screen.TABLE -> {
                    val online = onlineSession
                    val onlineTable = online?.table
                    val table = onlineTable ?: session?.table
                    if (table != null) {
                        TableScreen(
                            table = table,
                            onDiscard = {
                                if (onlineTable != null) online?.discard(it) else session?.discard(it)
                            },
                            onClaim = { option, tiles ->
                                if (onlineTable != null) online?.claim(option, tiles) else session?.claim(option, tiles)
                            },
                            onKong = {
                                if (onlineTable != null) online?.ownKong(it) else session?.ownKong(it)
                            },
                            onHu = {
                                if (onlineTable != null) online?.ownHu() else session?.ownHu()
                            },
                            onExit = {
                                if (onlineTable != null) leaveOnlineToLobby() else backToLobby()
                            },
                            tileScale = if (settings.tileScale == TileScale.LARGE) 1.15f else 1f,
                            myName = settings.nickname,
                            showTing = settings.showTing,
                            onToggleTing = { update(settings.copy(showTing = !settings.showTing)) },
                            autoPlaying = online?.autoPlaying ?: session?.autoPlay ?: false,
                            onToggleAuto = if (onlineTable == null) {
                                { session?.toggleAuto() }
                            } else {
                                null
                            },
                        )
                    } else {
                        Box(
                            Modifier.fillMaxSize().background(colors.table2),
                            contentAlignment = Alignment.Center,
                        ) {
                            MjText("正在发牌…", type.label, colors.onTable2)
                        }
                    }
                }

                Screen.SETTLEMENT -> {
                    val ui = settlement
                    if (ui != null) {
                        LaunchedEffect(ui) {
                            // 荒庄已由对局内播过“流局”，这里只给正常结算补收束音。
                            if (settings.sound && !ui.draw) {
                                GameAudio.playEffect(SoundEffect.WIN_STING, settings.volume)
                            }
                        }
                        SettlementScreen(
                            settlement = ui,
                            onAgain = {
                                if (settlementOnline) {
                                    onlineSession?.backToRoom()
                                    settlement = null
                                    settlementOnline = false
                                    screen = Screen.ONLINE
                                } else {
                                    playAgain()
                                }
                            },
                            onLobby = {
                                if (settlementOnline) {
                                    leaveOnlineToLobby()
                                } else {
                                    backToLobby()
                                }
                            },
                        )
                    } else {
                        LaunchedEffect(Unit) { screen = Screen.LOBBY }
                    }
                }

                Screen.SETTINGS -> SettingsScreen(
                    settings = settings,
                    onChange = { update(it) },
                    onBack = { screen = settingsReturn },
                    onToast = { showToast(it) },
                )

                Screen.RECORDS -> RecordsScreen(
                    onBack = { screen = Screen.LOBBY },
                    onLobby = { screen = Screen.LOBBY },
                    onSettings = {
                        settingsReturn = Screen.RECORDS
                        screen = Screen.SETTINGS
                    },
                )

                Screen.ONLINE -> {
                    val online = onlineSession
                    if (online != null) {
                        OnlineScreen(
                            session = online,
                            settings = settings,
                            onBack = { leaveOnlineToLobby() },
                            onSettings = {
                                settingsReturn = Screen.ONLINE
                                screen = Screen.SETTINGS
                            },
                            onToast = { showToast(it) },
                        )
                    } else {
                        LaunchedEffect(Unit) { screen = Screen.LOBBY }
                    }
                }
            }

            AnimatedVisibility(
                visible = toast != null,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(MjRadius.control))
                        .background(colors.ink.copy(alpha = 0.92f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    MjText(toast ?: "", type.label, colors.surface2)
                }
            }
        }
    }
}
