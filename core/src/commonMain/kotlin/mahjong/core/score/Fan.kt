package mahjong.core.score

/**
 * 国标麻将 81 番种表（按官方 88/64/48/32/24/16/12/8/6/4/2/1 分分级）。
 */
enum class Fan(val displayName: String, val value: Int) {
    BIG_FOUR_WINDS("大四喜", 88),
    BIG_THREE_DRAGONS("大三元", 88),
    ALL_GREEN("绿一色", 88),
    NINE_GATES("九莲宝灯", 88),
    FOUR_KONGS("四杠", 88),
    SEVEN_SHIFTED_PAIRS("连七对", 88),
    THIRTEEN_ORPHANS("十三幺", 88),

    ALL_TERMINALS("清幺九", 64),
    LITTLE_FOUR_WINDS("小四喜", 64),
    LITTLE_THREE_DRAGONS("小三元", 64),
    ALL_HONORS("字一色", 64),
    FOUR_CONCEALED_PUNGS("四暗刻", 64),
    PURE_TERMINAL_CHOWS("一色双龙会", 64),

    QUADRUPLE_CHOW("一色四同顺", 48),
    FOUR_PURE_SHIFTED_PUNGS("一色四节高", 48),

    FOUR_PURE_SHIFTED_CHOWS("一色四步高", 32),
    THREE_KONGS("三杠", 32),
    ALL_TERMINALS_AND_HONORS("混幺九", 32),

    SEVEN_PAIRS("七对", 24),
    GREATER_HONORS_AND_KNITTED("七星不靠", 24),
    ALL_EVEN_PUNGS("全双刻", 24),
    FULL_FLUSH("清一色", 24),
    PURE_TRIPLE_CHOW("一色三同顺", 24),
    PURE_SHIFTED_PUNGS("一色三节高", 24),
    UPPER_TILES("全大", 24),
    MIDDLE_TILES("全中", 24),
    LOWER_TILES("全小", 24),

    PURE_STRAIGHT("清龙", 16),
    THREE_SUITED_TERMINAL_CHOWS("三色双龙会", 16),
    PURE_SHIFTED_CHOWS("一色三步高", 16),
    ALL_FIVES("全带五", 16),
    TRIPLE_PUNG("三同刻", 16),
    THREE_CONCEALED_PUNGS("三暗刻", 16),

    LESSER_HONORS_AND_KNITTED("全不靠", 12),
    KNITTED_STRAIGHT("组合龙", 12),
    UPPER_FOUR("大于五", 12),
    LOWER_FOUR("小于五", 12),
    BIG_THREE_WINDS("三风刻", 12),

    HUA_DRAGON("花龙", 8),
    REVERSIBLE_TILES("推不倒", 8),
    MIXED_TRIPLE_CHOW("三色三同顺", 8),
    MIXED_SHIFTED_PUNGS("三色三节高", 8),
    LAST_TILE_DRAW("妙手回春", 8),
    LAST_TILE_CLAIM("海底捞月", 8),
    OUT_WITH_REPLACEMENT("杠上开花", 8),
    ROBBING_THE_KONG("抢杠和", 8),
    NO_FAN_HAND("无番和", 8),

    ALL_PUNGS("碰碰和", 6),
    HALF_FLUSH("混一色", 6),
    MIXED_SHIFTED_CHOWS("三色三步高", 6),
    ALL_TYPES("五门齐", 6),
    MELDED_HAND("全求人", 6),
    TWO_CONCEALED_KONGS("双暗杠", 6),
    TWO_DRAGON_PUNGS("双箭刻", 6),

    ALL_WITH_TERMINALS("全带幺", 4),
    FULLY_CONCEALED("不求人", 4),
    TWO_MELDED_KONGS("双明杠", 4),
    LAST_TILE("和绝张", 4),

    DRAGON_PUNG("箭刻", 2),
    ROUND_WIND_PUNG("圈风刻", 2),
    SEAT_WIND_PUNG("门风刻", 2),
    FRONT_CONCEALED("门前清", 2),
    PING_HU("平和", 2),
    FOUR_OF_A_KIND("四归一", 2),
    DOUBLE_PUNG("双同刻", 2),
    CONCEALED_KONG("暗杠", 2),
    TWO_CONCEALED_PUNGS("双暗刻", 2),
    ALL_SIMPLES("断幺", 2),

    PURE_DOUBLE_CHOW("一般高", 1),
    MIXED_DOUBLE_CHOW("喜相逢", 1),
    SHORT_STRAIGHT("连六", 1),
    TWO_TERMINAL_CHOWS("老少副", 1),
    TERMINAL_HONOR_PUNG("幺九刻", 1),
    MELDED_KONG("明杠", 1),
    MISSING_ONE_SUIT("缺一门", 1),
    NO_HONORS("无字", 1),
    EDGE_WAIT("边张", 1),
    CLOSED_WAIT("坎张", 1),
    SINGLE_WAIT("单钓将", 1),
    SELF_DRAW("自摸", 1),
    FLOWER("花牌", 1),
    ;

    val id: String get() = name

    companion object {
        fun byName(name: String): Fan? = entries.firstOrNull { it.name == name }
    }
}

object FanTable {
    /**
     * 国标“不重复计”原则：
     * 高番成立时，剔除其定义中必然包含的低番；没有得到包含关系的番种仍可叠加。
     */
    val exclusions: Map<Fan, Set<Fan>> = mapOf(
        Fan.BIG_FOUR_WINDS to setOf(
            Fan.LITTLE_FOUR_WINDS, Fan.BIG_THREE_WINDS, Fan.ROUND_WIND_PUNG, Fan.SEAT_WIND_PUNG,
            Fan.ALL_PUNGS, Fan.TERMINAL_HONOR_PUNG,
        ),
        Fan.LITTLE_FOUR_WINDS to setOf(
            Fan.BIG_THREE_WINDS, Fan.ROUND_WIND_PUNG, Fan.SEAT_WIND_PUNG,
            Fan.ALL_PUNGS, Fan.TERMINAL_HONOR_PUNG,
        ),
        Fan.BIG_THREE_DRAGONS to setOf(Fan.LITTLE_THREE_DRAGONS, Fan.TWO_DRAGON_PUNGS, Fan.DRAGON_PUNG),
        Fan.LITTLE_THREE_DRAGONS to setOf(Fan.TWO_DRAGON_PUNGS, Fan.DRAGON_PUNG),
        Fan.FOUR_CONCEALED_PUNGS to setOf(
            Fan.THREE_CONCEALED_PUNGS, Fan.TWO_CONCEALED_PUNGS, Fan.FRONT_CONCEALED, Fan.ALL_PUNGS,
        ),
        Fan.THREE_CONCEALED_PUNGS to setOf(Fan.TWO_CONCEALED_PUNGS),
        Fan.FOUR_KONGS to setOf(
            Fan.THREE_KONGS, Fan.TWO_CONCEALED_KONGS, Fan.TWO_MELDED_KONGS,
            Fan.CONCEALED_KONG, Fan.MELDED_KONG,
        ),
        Fan.THREE_KONGS to setOf(Fan.TWO_CONCEALED_KONGS, Fan.TWO_MELDED_KONGS, Fan.CONCEALED_KONG, Fan.MELDED_KONG),
        Fan.TWO_CONCEALED_KONGS to setOf(Fan.CONCEALED_KONG),
        Fan.TWO_MELDED_KONGS to setOf(Fan.MELDED_KONG),
        Fan.FULL_FLUSH to setOf(Fan.HALF_FLUSH, Fan.MISSING_ONE_SUIT, Fan.NO_HONORS),
        Fan.HALF_FLUSH to setOf(Fan.MISSING_ONE_SUIT),
        Fan.ALL_HONORS to setOf(
            Fan.HALF_FLUSH, Fan.ALL_PUNGS, Fan.ALL_TERMINALS_AND_HONORS, Fan.ALL_WITH_TERMINALS,
        ),
        Fan.ALL_TERMINALS to setOf(
            Fan.ALL_TERMINALS_AND_HONORS, Fan.ALL_PUNGS, Fan.ALL_WITH_TERMINALS,
            Fan.NO_HONORS, Fan.TRIPLE_PUNG, Fan.DOUBLE_PUNG,
        ),
        Fan.ALL_TERMINALS_AND_HONORS to setOf(Fan.ALL_WITH_TERMINALS, Fan.ALL_PUNGS),
        Fan.ALL_EVEN_PUNGS to setOf(Fan.ALL_PUNGS, Fan.ALL_SIMPLES),
        Fan.SEVEN_PAIRS to setOf(Fan.FRONT_CONCEALED, Fan.SINGLE_WAIT, Fan.FULLY_CONCEALED),
        Fan.SEVEN_SHIFTED_PAIRS to setOf(
            Fan.SEVEN_PAIRS, Fan.FULL_FLUSH, Fan.FRONT_CONCEALED, Fan.SINGLE_WAIT, Fan.FULLY_CONCEALED,
        ),
        Fan.THIRTEEN_ORPHANS to setOf(
            Fan.ALL_TYPES, Fan.ALL_TERMINALS_AND_HONORS, Fan.FRONT_CONCEALED, Fan.SINGLE_WAIT,
            Fan.ALL_WITH_TERMINALS, Fan.FULLY_CONCEALED,
        ),
        Fan.NINE_GATES to setOf(Fan.FULL_FLUSH, Fan.FRONT_CONCEALED, Fan.TERMINAL_HONOR_PUNG, Fan.SINGLE_WAIT),
        Fan.ALL_GREEN to setOf(Fan.HALF_FLUSH),
        Fan.QUADRUPLE_CHOW to setOf(
            Fan.PURE_TRIPLE_CHOW, Fan.PURE_SHIFTED_PUNGS, Fan.PURE_DOUBLE_CHOW, Fan.FOUR_OF_A_KIND,
        ),
        Fan.FOUR_PURE_SHIFTED_PUNGS to setOf(Fan.PURE_TRIPLE_CHOW, Fan.PURE_SHIFTED_PUNGS, Fan.ALL_PUNGS),
        Fan.FOUR_PURE_SHIFTED_CHOWS to setOf(Fan.PURE_SHIFTED_CHOWS, Fan.PURE_STRAIGHT),
        Fan.PURE_TERMINAL_CHOWS to setOf(Fan.PING_HU, Fan.SEVEN_PAIRS, Fan.FULL_FLUSH),
        Fan.THREE_SUITED_TERMINAL_CHOWS to setOf(
            Fan.MIXED_DOUBLE_CHOW, Fan.TWO_TERMINAL_CHOWS, Fan.NO_HONORS, Fan.PING_HU,
        ),
        Fan.ALL_FIVES to setOf(Fan.ALL_SIMPLES),
        Fan.FULLY_CONCEALED to setOf(Fan.FRONT_CONCEALED, Fan.SELF_DRAW),
        Fan.MELDED_HAND to setOf(Fan.SINGLE_WAIT),
        Fan.UPPER_TILES to setOf(Fan.UPPER_FOUR),
        Fan.MIDDLE_TILES to setOf(Fan.ALL_SIMPLES),
        Fan.LOWER_TILES to setOf(Fan.LOWER_FOUR),
        Fan.TWO_DRAGON_PUNGS to setOf(Fan.DRAGON_PUNG),
        Fan.TRIPLE_PUNG to setOf(Fan.DOUBLE_PUNG),
        Fan.REVERSIBLE_TILES to setOf(Fan.MISSING_ONE_SUIT),
        Fan.OUT_WITH_REPLACEMENT to setOf(Fan.SELF_DRAW),
        Fan.LAST_TILE_DRAW to setOf(Fan.SELF_DRAW),
        Fan.ROBBING_THE_KONG to setOf(Fan.LAST_TILE),
        // 无番和与和牌方式类番种同时成立时只取其一，不叠加。
        Fan.NO_FAN_HAND to setOf(Fan.SELF_DRAW, Fan.FRONT_CONCEALED, Fan.FULLY_CONCEALED),
    )

    fun apply(items: List<FanItem>): List<FanItem> {
        if (items.isEmpty()) return items
        val present = items.map { it.id }.toSet()
        val removed = HashSet<String>()
        for (fan in Fan.entries.sortedByDescending { it.value }) {
            if (fan.id !in present || fan.id in removed) continue
            exclusions[fan]?.forEach { removed.add(it.id) }
        }
        return items.filter { it.id !in removed }
    }
}
