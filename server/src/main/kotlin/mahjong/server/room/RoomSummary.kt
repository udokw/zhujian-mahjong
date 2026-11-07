package mahjong.server.room

/**
 * 对外可见的房间摘要（局域网发现 / 大厅列表用）。
 * 只包含公开信息：不含 resumeToken、手牌、密码哈希。
 */
data class RoomSummary(
    val code: String,
    val ruleSet: String,
    val phase: String,
    val host: String,
    val players: Int,
    val humans: Int,
    val capacity: Int = 4,
    val hasPassword: Boolean = false,
)
