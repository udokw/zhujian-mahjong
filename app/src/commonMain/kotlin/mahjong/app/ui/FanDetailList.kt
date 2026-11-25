package mahjong.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mahjong.app.theme.LocalMjColors
import mahjong.app.theme.LocalMjType
import mahjong.core.score.FanItem

/**
 * 番型清单的一行。
 *
 * 结算页与战绩详情页共用这一份渲染：结算页由实时 [FanItem] 转换而来，
 * 详情页由写入记录时的快照转换而来，避免两套展示走偏。
 */
data class FanLine(
    val id: String,
    val name: String,
    val fan: Int,
    val totalFan: Int,
    val count: Int,
    /** 是否计入起和门槛；无门槛的玩法统一为 false。 */
    val countsTowardMin: Boolean,
)

/** 结算时的番型 → 清单行；门槛判定与快照写入共用这套规则。 */
fun fanLinesOf(fans: List<FanItem>, minFan: Int, flowerCountsTowardMin: Boolean): List<FanLine> =
    fans.map { item ->
        FanLine(
            id = item.id,
            name = item.name,
            fan = item.fan,
            totalFan = item.totalFan,
            count = item.count,
            countsTowardMin = countsTowardThreshold(item.id, minFan, flowerCountsTowardMin),
        )
    }

/** 只看 id，别用名称匹配：杠上开花 / 花龙 名字里也有「花」。 */
fun countsTowardThreshold(itemId: String, minFan: Int, flowerCountsTowardMin: Boolean): Boolean = when {
    minFan <= 0 -> false
    flowerCountsTowardMin -> true
    else -> !(itemId == "花牌" || itemId.startsWith("gd_flower"))
}

@Composable
fun FanDetailList(
    lines: List<FanLine>,
    minFan: Int,
    modifier: Modifier = Modifier,
    /** 外层已有纵向滚动时置 false，避免同方向嵌套滚动。 */
    scrollable: Boolean = true,
) {
    if (lines.size <= 6 || !scrollable) {
        Column(if (scrollable) modifier.verticalScroll(rememberScrollState()) else modifier) {
            lines.forEach { FanRow(it, minFan) }
        }
    } else {
        val half = (lines.size + 1) / 2
        Row(modifier) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                lines.take(half).forEach { FanRow(it, minFan) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                lines.drop(half).forEach { FanRow(it, minFan) }
            }
        }
    }
}

@Composable
private fun FanRow(item: FanLine, minFan: Int) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    var expanded by remember(item.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (item.totalFan >= 6) colors.gold else colors.lineStrong),
            )
            Spacer(Modifier.width(6.dp))
            MjText(
                if (item.count > 1) "${item.name} ×${item.count}" else item.name,
                type.caption.copy(fontSize = 10.sp),
                colors.ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (minFan > 0 && !item.countsTowardMin) {
                MjText("不计门槛", type.caption.copy(fontSize = 7.sp), colors.ink3)
                Spacer(Modifier.width(4.dp))
            }
            MjText(
                if (item.totalFan == 0) "—" else "${item.totalFan}",
                type.caption.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                if (item.fan >= 6) colors.gold else colors.ink2,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(2.dp))
            MjText(
                fanDescription(item) + " · 计 ${item.totalFan} 番" +
                    when {
                        minFan <= 0 -> "，本玩法无起和门槛"
                        item.countsTowardMin -> "，计入起和门槛"
                        else -> "，不计入起和门槛"
                    },
                type.caption.copy(fontSize = 8.sp),
                colors.ink3,
            )
        }
    }
}

private fun fanDescription(item: FanLine): String {
    fanDescriptions[item.name]?.let { return it }
    if (item.count > 1) return "共 ${item.count} 组，每组 ${item.fan} 番"
    return "计 ${item.totalFan} 番"
}

private val fanDescriptions = mapOf(
    "大四喜" to "东、南、西、北四副风刻（含杠）",
    "小四喜" to "三副风刻加一对风牌作将",
    "大三元" to "中、发、白三副箭刻",
    "小三元" to "两副箭刻加一对箭牌作将",
    "绿一色" to "全部由二三四六八条和发财组成",
    "九莲宝灯" to "同花色 1112345678999 加任意一张",
    "四杠" to "整局共开出四副杠",
    "连七对" to "同花色七个连续对子",
    "十三幺" to "幺九字牌各一张，其中一对作将",
    "清幺九" to "全部由幺九牌组成",
    "字一色" to "全部由字牌组成",
    "四暗刻" to "四副暗刻，门前清",
    "一色双龙会" to "同花色两个老少副加五作将",
    "一色四同顺" to "同花色四副相同顺子",
    "一色四节高" to "同花色四副依次递增的刻子",
    "一色四步高" to "同花色四副依次递增一步的顺子",
    "三杠" to "整局共开出三副杠",
    "混幺九" to "全部由幺九与字牌组成",
    "七对" to "七个对子，门前清",
    "七星不靠" to "七种字牌各一张，序数牌间隔不少于三",
    "全双刻" to "全部由二四六八组成的刻子",
    "清一色" to "全部由同一花色组成",
    "一色三同顺" to "同花色三副相同顺子",
    "一色三节高" to "同花色三副依次递增的刻子",
    "全大" to "全部由七八九组成",
    "全中" to "全部由四五六组成",
    "全小" to "全部由一二三组成",
    "清龙" to "同花色一二三、四五六、七八九",
    "三色双龙会" to "两种花色各一个老少副，五作将",
    "一色三步高" to "同花色三副依次递增一步的顺子",
    "全带五" to "每副面子和将牌都含五",
    "三同刻" to "三种花色同一数字的刻子",
    "三暗刻" to "三副暗刻",
    "全不靠" to "字牌与序数牌互不相连且无重复",
    "组合龙" to "三种花色的特殊顺子组合",
    "大于五" to "全部由六七八九组成",
    "小于五" to "全部由一二三四组成",
    "三风刻" to "三副风牌刻子",
    "花龙" to "三种花色分别构成一二三、四五六、七八九",
    "推不倒" to "全部由可倒置牌组成",
    "三色三同顺" to "三种花色各一副相同顺子",
    "三色三节高" to "三种花色依次递增的刻子",
    "无番和" to "不满足任何番型但可和牌",
    "碰碰和" to "四副刻子加一对将",
    "混一色" to "一种花色加字牌",
    "五门齐" to "万条筒风箭五门齐全",
    "全求人" to "全部靠吃碰后单钓和牌",
    "双暗杠" to "两副暗杠",
    "双箭刻" to "两副箭牌刻子",
    "全带幺" to "每副面子与将牌都含幺九或字牌",
    "不求人" to "门前清且自摸",
    "双明杠" to "两副明杠",
    "和绝张" to "和牌张已现三张",
    "门前清" to "未吃碰明杠",
    "平和" to "四副顺子加序数牌将",
    "四归一" to "四张相同牌分散在面子中",
    "双同刻" to "两种花色同一数字的刻子",
    "双暗刻" to "两副暗刻",
    "暗杠" to "自己摸齐四张杠出",
    "断幺" to "全部由二至八组成",
    "一般高" to "同花色两副相同顺子",
    "喜相逢" to "两种花色相同顺子",
    "连六" to "同花色两副相连六张的顺子",
    "老少副" to "同花色一二三与七八九",
    "幺九刻" to "幺九牌的刻子",
    "明杠" to "碰后补杠或明杠他人打出的牌",
    "缺一门" to "缺少一种花色",
    "无字" to "不含字牌",
    "边张" to "和牌张为顺子的边张",
    "坎张" to "和牌张为顺子的中间张",
    "单钓将" to "单吊将牌和牌",
    "自摸" to "自己摸到和牌张",
    "花牌" to "摸到花牌补花计分",
    "鸡胡" to "最基础的胡牌，按底分计",
    "平胡" to "四副顺子加一对将",
    "碰碰胡" to "四副刻子加一对将",
    "杠上花" to "开杠后补牌自摸",
    "抢杠胡" to "抢他人补杠的牌和牌",
    "海底捞月" to "摸最后一张牌自摸",
    "天胡" to "庄家起手即和",
    "地胡" to "闲家第一巡和牌",
    "买马" to "和牌后翻马牌，按命中数加算",
)
