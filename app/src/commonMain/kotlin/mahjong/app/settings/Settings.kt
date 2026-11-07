package mahjong.app.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mahjong.app.keyValueStore
import mahjong.app.platformDefaultServerUrl
import mahjong.core.ai.AiLevel
import mahjong.core.rules.GameMode
import mahjong.core.rules.RuleConfig
import mahjong.core.rules.RulePreset
import mahjong.app.theme.TableSkin
import mahjong.app.theme.ThemeMode

@Serializable
enum class TileScale { STANDARD, LARGE }

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val skin: TableSkin = TableSkin.CLASSIC,
    val tileScale: TileScale = TileScale.STANDARD,
    val aiLevelId: String = AiLevel.NORMAL.id,
    val sound: Boolean = true,
    val voice: Boolean = true,
    val haptics: Boolean = true,
    val reduceMotion: Boolean = false,
    val volume: Float = 0.7f,
    val rulePresetId: String = RulePreset.GD_JIPINGHU.id,
    val flowerFan: Boolean = true,
    val robKong: Boolean = true,
    val multipleWinners: Boolean = false,
    val baoPai: Boolean = true,
    val hintHu: Boolean = true,
    val showTing: Boolean = true,
    val nickname: String = "林知远",
    val serverUrl: String = "",
    val arenaIndex: Int = 0,
) {
    val aiLevel: AiLevel get() = AiLevel.fromId(aiLevelId) ?: AiLevel.NORMAL
    val preset: RulePreset get() = RulePreset.fromId(rulePresetId) ?: RulePreset.GD_JIPINGHU
    val effectiveServerUrl: String get() = serverUrl.ifBlank { platformDefaultServerUrl() }

    /** 把设置里的开关映射成引擎规则，保证界面上的开关真的影响牌局。 */
    fun ruleConfig(): RuleConfig {
        val base = RuleConfig.of(preset)
        val withFlowers = if (base.useFlowers) flowerFan else false
        return base.copy(
            useFlowers = withFlowers,
            flowerFan = if (withFlowers) base.flowerFan else 0,
            allowRobKong = robKong && base.allowRobKong,
            multipleWinnersOnDiscard = if (base.mode == GameMode.MCR) false else multipleWinners,
            baoPai = baoPai && base.baoPai,
        )
    }
}

object SettingsRepository {
    private const val KEY = "app_settings_v1"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): AppSettings {
        val raw = runCatching { keyValueStore().get(KEY) }.getOrNull() ?: return AppSettings()
        return runCatching { json.decodeFromString(AppSettings.serializer(), raw) }
            .getOrElse { AppSettings() }
    }

    fun save(settings: AppSettings) {
        runCatching {
            keyValueStore().put(KEY, json.encodeToString(AppSettings.serializer(), settings))
        }
    }
}

/** 大厅场次：名称、底分、入场分。AI 难度与设置中的四档共用一份。 */
data class Arena(
    val name: String,
    val baseScore: Int,
    val entrance: String,
    val aiLevel: AiLevel,
)

val Arenas = listOf(
    Arena("新手场", 1, "入场免费", AiLevel.NOVICE),
    Arena("进阶场", 2, "入场 2,000", AiLevel.NORMAL),
    Arena("高手场", 5, "入场 20,000", AiLevel.EXPERT),
    Arena("大师场", 10, "入场 200,000", AiLevel.MASTER),
)
