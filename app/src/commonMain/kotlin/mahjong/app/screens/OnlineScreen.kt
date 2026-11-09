package mahjong.app.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mahjong.app.game.OnlineGameSession
import mahjong.app.game.OnlineStatus
import mahjong.app.game.RoomStateDto
import mahjong.app.game.SeatInfoDto
import mahjong.app.lan.LanDiag
import mahjong.app.lan.LanLog
import mahjong.app.lan.LanPeer
import mahjong.app.settings.AppSettings
import mahjong.app.theme.LocalMjColors
import mahjong.app.theme.LocalMjType
import mahjong.app.theme.MjColors
import mahjong.app.theme.MjRadius
import mahjong.app.theme.MjSpace
import mahjong.app.ui.Avatar
import mahjong.app.ui.DialogMessage
import mahjong.app.ui.DialogTitle
import mahjong.app.ui.DividerLine
import mahjong.app.ui.GhostButton
import mahjong.app.ui.MjCard
import mahjong.app.ui.MjDialog
import mahjong.app.ui.MjIcon
import mahjong.app.ui.MjIconKind
import mahjong.app.ui.MjText
import mahjong.app.ui.PrimaryButton
import mahjong.app.ui.SettingsGroup
import mahjong.app.ui.SettingRow
import mahjong.app.ui.SmallButton

@Composable
fun OnlineScreen(
    session: OnlineGameSession,
    settings: AppSettings,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onToast: (String) -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    var roomInput by remember { mutableStateOf("") }
    var joinTarget by remember { mutableStateOf<LanPeer?>(null) }
    var kickTarget by remember { mutableStateOf<SeatInfoDto?>(null) }
    var aiTarget by remember { mutableStateOf<SeatInfoDto?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showLanHint by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val room = session.room
    val connected = session.status == OnlineStatus.CONNECTED
    val nearbyRooms by session.lobbyPeers.collectAsState()
    val scanning = room == null && nearbyRooms.isEmpty()

    // 大厅里常驻一个「客机发现」节点；进房或建房时自动停掉，把发现端口让给房主。
    DisposableEffect(room == null) {
        if (room == null) session.startLobbyScan() else session.stopLobbyScan()
        onDispose {
            if (room == null) session.stopLobbyScan()
        }
    }

    LaunchedEffect(room == null, session.status) {
        // 加入失败会停掉发现节点；回到空闲态要自动重开，否则列表永远停在失败前那一份。
        if (room == null && session.status == OnlineStatus.ERROR) session.refreshLobby()
    }

    LaunchedEffect(session.notice) {
        session.notice?.let {
            onToast(it)
        }
    }
    LaunchedEffect(session.errorMessage) {
        session.errorMessage?.let { onToast(it) }
    }

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    MjIcon(MjIconKind.BACK, size = 18.dp, tint = colors.ink2)
                }
                Spacer(Modifier.weight(1f))
                // 联机状态做成轻量指示，放在设置图标左侧；点击才展开说明。
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { showLanHint = !showLanHint }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(statusDotColor(session.status, colors)),
                    )
                    Spacer(Modifier.width(6.dp))
                    MjText(
                        statusLabel(session.status),
                        type.caption.copy(fontSize = 10.sp),
                        colors.ink2,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSettings() },
                    contentAlignment = Alignment.Center,
                ) {
                    MjIcon(MjIconKind.GEAR, size = 17.dp, tint = colors.ink2)
                }
            }

            AnimatedVisibility(visible = showLanHint) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MjSpace.page, vertical = 4.dp)
                        .clip(RoundedCornerShape(MjRadius.control))
                        .background(colors.surface)
                        .border(1.dp, colors.line, RoundedCornerShape(MjRadius.control))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(statusDotColor(session.status, colors)),
                    )
                    Spacer(Modifier.width(8.dp))
                    MjText(
                        if (session.isHost) {
                            "房主 · 局域网直连 · 房号 ${session.room?.room ?: "-"}"
                        } else {
                            "局域网直连，已自动发现"
                        },
                        type.caption.copy(fontSize = 10.sp),
                        colors.ink3,
                        maxLines = 1,
                    )
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MjSpace.page),
            ) {
                Spacer(Modifier.height(6.dp))
                if (room != null) {
                    MjText("房间 ${room.room}", type.display, colors.ink)
                    Spacer(Modifier.height(4.dp))
                }
                MjText(
                    if (room != null) "邀请好友，空位由 AI 补齐" else "创建或加入房间，四人一桌",
                    type.caption,
                    colors.ink3,
                )

                Spacer(Modifier.height(16.dp))

                if (session.status == OnlineStatus.ERROR) {
                    Spacer(Modifier.height(12.dp))
                    MjCard {
                        MjText("联机未连上", type.bodyStrong, colors.ink)
                        Spacer(Modifier.height(4.dp))
                        MjText(
                            session.errorMessage ?: "请确认两台设备在同一 Wi-Fi 下",
                            type.caption,
                            colors.ink3,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton(
                                "重试",
                                {
                                    if (room != null && session.hasSavedSeat) {
                                        session.resumeSaved()
                                    } else {
                                        // 加入失败后重拉列表，而不是对着已经不存在的旧房间反复重试。
                                        session.refreshLobby()
                                        onToast("已刷新附近房间")
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            GhostButton("联机诊断", { showDiagnostics = true }, modifier = Modifier.weight(1f))
                        }
                    }
                }

                if (room == null) {
                    Spacer(Modifier.height(16.dp))
                    // 「未完成牌局」放页面最上方，进入好友房第一眼就能回座。
                    if (session.hasSavedSeat) {
                        SavedRoomCard(session.savedRoom, onResume = { session.resumeSaved() })
                        Spacer(Modifier.height(12.dp))
                    }
                    val nearby: @Composable () -> Unit = {
                        NearbyRoomsCard(
                            rooms = nearbyRooms,
                            scanning = scanning,
                            onRefresh = { session.refreshLobby() },
                            onJoin = { found ->
                                if (found.hasPassword) joinTarget = found
                                else session.joinRoom(found.code, found.endpoint)
                            },
                        )
                    }
                    val entry: @Composable () -> Unit = {
                        EntryCard(
                            settings = settings,
                            roomInput = roomInput,
                            onRoomInput = { roomInput = it.filter { ch -> ch.isDigit() }.take(4) },
                            connecting = session.status == OnlineStatus.CONNECTING,
                            onCreate = { session.createRoom(settings.preset.id) },
                            onJoin = { session.joinRoom(roomInput) },
                        )
                    }

                    // 横屏双栏：左「创建房间」，右「附近房间」；竖屏按同顺序纵向堆叠。
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= 600.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f)) { entry() }
                                Column(Modifier.weight(1f)) { nearby() }
                            }
                        } else {
                            Column {
                                entry()
                                Spacer(Modifier.height(16.dp))
                                nearby()
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    // 说明抽屉的入口固定在底部一行：左「联机说明」、右「联机诊断」，
                    // 展开内容只出现在这一行下方，不挤动上面的创建/房间模块。
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton(
                            if (showHelp) "收起说明" else "联机说明",
                            { showHelp = !showHelp },
                            modifier = Modifier.weight(1f),
                        )
                        GhostButton("联机诊断", { showDiagnostics = true }, modifier = Modifier.weight(1f))
                    }
                    AnimatedVisibility(visible = showHelp) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            SettingsGroup("联机说明") {
                                SettingRow("发现", "同一 Wi-Fi 下建房后自动出现在「附近房间」", showDivider = true)
                                SettingRow("房号", "4 位数字，只填房号即可加入，不用填地址", showDivider = true)
                                SettingRow("座位", "四人一桌，房主可补 AI、可移出玩家", showDivider = true)
                                SettingRow("断线", "立即 AI 托管，10 分钟内可回座", showDivider = false)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                } else {
                    Spacer(Modifier.height(14.dp))
                    RoomCodeCard(
                        room = room,
                        onCopy = {
                            clipboard.setText(AnnotatedString(room.room))
                            onToast("房间号已复制")
                        },
                    )

                    Spacer(Modifier.height(14.dp))
                    SeatGrid(
                        session = session,
                        room = room,
                        onKickRequest = { kickTarget = it },
                        onAiRequest = { aiTarget = it },
                    )

                    Spacer(Modifier.height(16.dp))
                    RoomActions(session, room, onToast)
                }

                if (session.autoPlaying) {
                    Spacer(Modifier.height(12.dp))
                    MjText("当前处于 AI 托管状态", type.caption, colors.gold)
                }

                Spacer(Modifier.height(24.dp))
                GhostButton("返回大厅", onBack)
                Spacer(Modifier.height(28.dp))
            }
        }

        joinTarget?.let { target ->
            JoinPasswordDialog(
                target = target,
                onCancel = { joinTarget = null },
                onConfirm = { password ->
                    joinTarget = null
                    session.joinRoom(target.code, target.endpoint, password)
                },
            )
        }

        kickTarget?.let { target ->
            KickConfirmDialog(
                name = target.nickname.ifBlank { "座位 ${target.seat + 1}" },
                onCancel = { kickTarget = null },
                onConfirm = {
                    session.kick(target.seat)
                    kickTarget = null
                    onToast("已移出该玩家")
                },
            )
        }

        aiTarget?.let { target ->
            AiLevelDialog(
                seat = target.seat,
                replacingHuman = target.kind == "human",
                onCancel = { aiTarget = null },
                onPick = { level ->
                    session.addAi(target.seat, level)
                    aiTarget = null
                    onToast("${target.seat + 1} 座已设为 AI · ${aiLevelName(level)}")
                },
            )
        }

        if (showDiagnostics) {
            DiagnosticsDialog(
                onDismiss = { showDiagnostics = false },
                onCopy = { text ->
                    clipboard.setText(AnnotatedString(text))
                    showDiagnostics = false
                    onToast("联机日志已复制")
                },
            )
        }
    }
}

private fun statusLabel(status: OnlineStatus): String = when (status) {
    OnlineStatus.CONNECTED -> "已连接"
    OnlineStatus.CONNECTING -> "连接中"
    OnlineStatus.ERROR -> "未连上"
    OnlineStatus.OFFLINE -> "未连接"
}

private fun statusDotColor(status: OnlineStatus, colors: MjColors): Color = when (status) {
    OnlineStatus.CONNECTED -> colors.green
    OnlineStatus.CONNECTING -> colors.gold
    OnlineStatus.ERROR -> colors.red
    OnlineStatus.OFFLINE -> colors.disabled
}

@Composable
private fun EntryCard(
    settings: AppSettings,
    roomInput: String,
    onRoomInput: (String) -> Unit,
    connecting: Boolean,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    MjCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MjText("创建房间", type.bodyStrong, colors.ink)
                Spacer(Modifier.height(2.dp))
                MjText("规则：${settings.preset.displayName}", type.caption, colors.ink3)
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(if (connecting) "连接中…" else "创建房间", onCreate, enabled = !connecting)
        Spacer(Modifier.height(18.dp))
        DividerLine()
        Spacer(Modifier.height(16.dp))
        MjText("加入房间", type.bodyStrong, colors.ink)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(MjRadius.small))
                    .background(colors.surface2)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (roomInput.isEmpty()) {
                    MjText("输入 4 位数字房号", type.body, colors.disabled, maxLines = 1)
                }
                BasicTextField(
                    value = roomInput,
                    onValueChange = onRoomInput,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = colors.ink,
                        fontSize = type.title.fontSize,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(10.dp))
            SmallButton("加入", onJoin, enabled = roomInput.length == 4 && !connecting)
        }
    }
}

@Composable
private fun RoomCodeCard(room: RoomStateDto, onCopy: () -> Unit) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    MjCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MjText("房间号", type.caption, colors.ink3)
                Spacer(Modifier.height(3.dp))
                MjText(room.room, type.display.copy(fontSize = 26.sp, letterSpacing = 5.sp), colors.ink)
                Spacer(Modifier.height(4.dp))
                MjText(
                    when (room.ruleSet) {
                        "GD_TUIDAOHU" -> "广东推倒胡"
                        "GD_JIPINGHU" -> "广东鸡平胡"
                        else -> "国标麻将"
                    },
                    type.caption,
                    colors.ink3,
                )
            }
            SmallButton("复制", onCopy)
        }
    }
}

@Composable
private fun SeatGrid(
    session: OnlineGameSession,
    room: RoomStateDto,
    onKickRequest: (SeatInfoDto) -> Unit,
    onAiRequest: (SeatInfoDto) -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in 0 until 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (col in 0 until 2) {
                    val seat = row * 2 + col
                    val info = room.seats.firstOrNull { it.seat == seat }
                        ?: SeatInfoDto(seat = seat, kind = "empty")
                    SeatCard(
                        session = session,
                        seat = seat,
                        info = info,
                        isHost = seat == room.hostSeat,
                        onKick = if (session.isHost && info.kind == "human" && seat != session.mySeat) {
                            { onKickRequest(info) }
                        } else {
                            null
                        },
                        onAddAi = if (session.isHost && info.kind == "empty") {
                            { onAiRequest(info) }
                        } else {
                            null
                        },
                        onReplaceAi = if (session.isHost && info.kind == "human" && seat != session.mySeat) {
                            { onAiRequest(info) }
                        } else {
                            null
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (room.seats.any { it.kind == "empty" } && session.isHost) {
            GhostButton("AI 补齐空位", { session.fillWithAi() })
        }
    }
}

@Composable
private fun SeatCard(
    session: OnlineGameSession,
    seat: Int,
    info: SeatInfoDto,
    isHost: Boolean,
    modifier: Modifier = Modifier,
    onKick: (() -> Unit)? = null,
    onAddAi: (() -> Unit)? = null,
    onReplaceAi: (() -> Unit)? = null,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val mine = seat == session.mySeat
    val empty = info.kind == "empty"
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MjRadius.card))
            .background(if (mine) colors.primarySoft else colors.surface)
            .border(
                1.dp,
                if (mine) colors.primary else colors.line,
                RoundedCornerShape(MjRadius.card),
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(
                name = if (empty) "空" else info.nickname.ifBlank { "AI" },
                size = 32.dp,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                MjText(
                    when {
                        empty -> "空位"
                        info.kind == "ai" -> info.nickname.ifBlank { "AI" }
                        else -> info.nickname.ifBlank { "玩家" }
                    },
                    type.label.copy(fontWeight = FontWeight.SemiBold),
                    if (empty) colors.ink3 else colors.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.height(1.dp))
                MjText(
                    when {
                        empty -> "等待加入"
                        info.kind == "ai" -> "AI · ${aiLevelName(info.aiLevel)}"
                        !info.online -> "离线 · AI 托管"
                        info.ready -> "已准备"
                        else -> "未准备"
                    },
                    type.caption.copy(fontSize = 10.sp),
                    when {
                        !empty && !info.online -> colors.red
                        !empty && info.ready -> colors.green
                        else -> colors.ink3
                    },
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tags = buildList {
                add("${seat + 1} 座")
                if (isHost) add("房主")
                if (mine) add("我")
            }
            tags.forEach { tag ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.surface2)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    MjText(
                        tag,
                        type.caption.copy(fontSize = 9.sp),
                        colors.ink3,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Spacer(Modifier.weight(1f))
            if (onAddAi != null) {
                SmallButton("加 AI", onAddAi)
            } else if (info.kind == "ai" && session.isHost) {
                SmallButton("移除", { session.removeAi(seat) })
            } else if (onKick != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (onReplaceAi != null) {
                        SmallButton("换AI", onReplaceAi)
                    }
                    SmallButton("移出", onKick)
                }
            } else {
                MjText("${info.score}", type.caption, colors.ink3)
            }
        }
    }
}

@Composable
private fun RoomActions(
    session: OnlineGameSession,
    room: RoomStateDto,
    onToast: (String) -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val me = room.seats.firstOrNull { it.seat == session.mySeat }
    val humans = room.seats.filter { it.kind == "human" }
    val allSeated = room.seats.isNotEmpty() && room.seats.all { it.kind != "empty" }
    val allReady = humans.isNotEmpty() && humans.all { it.ready }
    val canStart = session.isHost && allSeated && allReady

    Column {
        if (me != null && me.kind == "human") {
            PrimaryButton(
                if (me.ready) "取消准备" else "准备",
                { session.setReady(!me.ready) },
                height = 48.dp,
            )
            Spacer(Modifier.height(10.dp))
        }
        if (session.isHost) {
            PrimaryButton(
                if (canStart) "开始对局" else if (!allSeated) "等待座位填满" else "等待其他玩家准备",
                { session.startGame() },
                enabled = canStart,
                height = 48.dp,
            )
            Spacer(Modifier.height(10.dp))
        }
        if (!allReady && humans.size > 1) {
            MjText("所有真人玩家准备后即可开始", type.caption, colors.ink3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        GhostButton("离开房间", { session.leave() })
    }
}

/** 线上 AI 等级沿用 core `AiLevel` 的四档：1 新手 / 2 普通 / 3 高手 / 4 大师。 */
private fun aiLevelName(level: Int): String = when (level) {
    1 -> "新手"
    2 -> "普通"
    3 -> "高手"
    4 -> "大师"
    else -> "AI"
}

private fun lanRuleLabel(ruleSet: String): String = when (ruleSet.uppercase()) {
    "GD_TUIDAOHU" -> "广东推倒胡"
    "GD_JIPINGHU" -> "广东鸡平胡"
    "MCR" -> "国标麻将"
    else -> ruleSet.ifBlank { "麻将" }
}

@Composable
private fun DiagnosticsDialog(
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val text = remember {
        "===== 联机诊断 =====\n" + LanDiag.snapshot() + "\n===== 最近日志 =====\n" + LanLog.dump()
    }
    MjDialog(onDismiss = onDismiss, maxWidth = 420.dp) {
        DialogTitle("联机诊断")
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(MjRadius.small))
                .background(colors.surface2)
                .padding(10.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MjText(text, type.caption.copy(fontSize = 10.sp), colors.ink2)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("关闭", onDismiss, modifier = Modifier.weight(1f))
            PrimaryButton("复制日志", { onCopy(text) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SavedRoomCard(savedRoom: String?, onResume: () -> Unit) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    MjCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MjText("有未完成的牌局", type.bodyStrong, colors.ink)
                Spacer(Modifier.height(2.dp))
                MjText("上次在 ${savedRoom ?: ""}，可直接回座继续", type.caption, colors.ink3)
            }
            SmallButton("重连", onResume)
        }
    }
}

@Composable
private fun NearbyRoomsCard(
    rooms: List<LanPeer>,
    scanning: Boolean,
    onRefresh: () -> Unit,
    onJoin: (LanPeer) -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    MjCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText("附近房间", type.bodyStrong, colors.ink)
            Spacer(Modifier.weight(1f))
            MjText(
                if (scanning) "搜索中…" else "${rooms.size} 个房间",
                type.caption,
                colors.ink3,
            )
            Spacer(Modifier.width(8.dp))
            SmallButton("刷新", onRefresh)
        }
        Spacer(Modifier.height(8.dp))
        if (rooms.isEmpty()) {
            MjText("同一 Wi-Fi 下有人创建房间后，会自动出现在这里", type.caption, colors.ink3)
        } else {
            rooms.forEach { found ->
                val joinable = found.phase == "waiting" && found.players < found.capacity
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = joinable) { onJoin(found) }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MjText(
                                found.code,
                                type.h2.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                colors.ink,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(10.dp))
                            MjText(
                                found.host,
                                type.caption.copy(fontSize = 9.sp),
                                colors.ink3,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.height(1.dp))
                        MjText(
                            "${lanRuleLabel(found.ruleSet)} · ${found.players}/${found.capacity} 人" +
                                if (found.hasPassword) " · 有密码" else "",
                            type.caption.copy(fontSize = 10.sp),
                            colors.ink3,
                            maxLines = 1,
                        )
                    }
                    MjText(
                        when {
                            found.phase != "waiting" -> "对局中"
                            found.players >= found.capacity -> "已满"
                            else -> "加入"
                        },
                        type.label,
                        if (joinable) colors.primary else colors.ink3,
                    )
                }
            }
        }
    }
}

@Composable
private fun JoinPasswordDialog(
    target: LanPeer,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    var password by remember(target.code) { mutableStateOf("") }
    MjDialog(onDismiss = onCancel, maxWidth = 340.dp) {
        DialogTitle("房间 ${target.code} 需要密码")
        Spacer(Modifier.height(6.dp))
        DialogMessage("${target.host} 的房间 · ${lanRuleLabel(target.ruleSet)}")
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MjRadius.small))
                .background(colors.surface2)
                .border(1.dp, colors.line, RoundedCornerShape(MjRadius.small))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            if (password.isEmpty()) {
                MjText("输入房间密码", type.body, colors.ink2, maxLines = 1)
            }
            BasicTextField(
                value = password,
                onValueChange = { password = it.take(12) },
                singleLine = true,
                textStyle = type.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("取消", onCancel, modifier = Modifier.weight(1f), height = 44.dp)
            PrimaryButton(
                "加入",
                { onConfirm(password) },
                modifier = Modifier.weight(1f),
                enabled = password.isNotBlank(),
                height = 44.dp,
            )
        }
    }
}

@Composable
private fun KickConfirmDialog(
    name: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    MjDialog(onDismiss = onCancel, maxWidth = 340.dp) {
        DialogTitle("移出玩家")
        Spacer(Modifier.height(8.dp))
        DialogMessage("「$name」将被移出房间，座位立即空出，其他玩家可以加入。若在牌局中移出，会由 AI 接管到本局结束。")
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("取消", onCancel, modifier = Modifier.weight(1f), height = 44.dp)
            PrimaryButton("移出", onConfirm, modifier = Modifier.weight(1f), height = 44.dp)
        }
    }
}

/** 房主给座位挑 AI 等级：1..4 = core AiLevel 的四档，和服务端/单机同一套。 */
@Composable
private fun AiLevelDialog(
    seat: Int,
    replacingHuman: Boolean,
    onCancel: () -> Unit,
    onPick: (Int) -> Unit,
) {
    MjDialog(onDismiss = onCancel, maxWidth = 340.dp) {
        DialogTitle("${seat + 1} 座 · 设为 AI")
        Spacer(Modifier.height(8.dp))
        DialogMessage(
            if (replacingHuman) {
                "这个座位上的玩家会被移出房间（收到提示），随后由 AI 顶替。"
            } else {
                "AI 在服务端跑，其他玩家看到的手牌和分数都由服务端统一计算。"
            },
        )
        Spacer(Modifier.height(14.dp))
        for (level in 1..4) {
            GhostButton(
                "AI · ${aiLevelName(level)}",
                { onPick(level) },
                modifier = Modifier.fillMaxWidth(),
                height = 42.dp,
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(4.dp))
        GhostButton("取消", onCancel, modifier = Modifier.fillMaxWidth(), height = 42.dp)
    }
}
