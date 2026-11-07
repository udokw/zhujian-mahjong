package mahjong.core.model

import mahjong.core.tile.Tiles

enum class MeldType {
    CHOW,
    PUNG,
    KONG_EXPOSED,
    KONG_CONCEALED,
    KONG_ADDED,
}

/**
 * 已亮出（或暗杠）的一组牌。CHOW/PUNG 为 3 张，KONG 为 4 张。
 */
data class Meld(
    val type: MeldType,
    val tiles: List<Int>,
    val claimedFrom: Int = -1,
    val claimedTile: Int = -1,
) {
    init {
        require(tiles.size == if (isKong) 4 else 3) { "illegal meld size: ${tiles.size} for $type" }
    }

    val isKong: Boolean
        get() = type == MeldType.KONG_EXPOSED || type == MeldType.KONG_CONCEALED || type == MeldType.KONG_ADDED

    val isConcealed: Boolean
        get() = type == MeldType.KONG_CONCEALED

    val isChow: Boolean
        get() = type == MeldType.CHOW

    /** 刻/杠的牌值；顺子返回起始牌。 */
    val baseTile: Int
        get() = tiles.minOrNull() ?: -1

    val suit: Int
        get() = Tiles.suit(baseTile)

    fun sortTiles(): Meld = copy(tiles = tiles.sorted())

    companion object {
        fun chow(tiles: List<Int>, from: Int, claimed: Int): Meld =
            Meld(MeldType.CHOW, tiles.sorted(), from, claimed)

        fun pung(tile: Int, from: Int): Meld = Meld(MeldType.PUNG, List(3) { tile }, from, tile)

        fun exposedKong(tile: Int, from: Int): Meld = Meld(MeldType.KONG_EXPOSED, List(4) { tile }, from, tile)

        fun concealedKong(tile: Int): Meld = Meld(MeldType.KONG_CONCEALED, List(4) { tile }, -1, tile)

        fun addedKong(tile: Int, from: Int): Meld = Meld(MeldType.KONG_ADDED, List(4) { tile }, from, tile)
    }
}

/** 手牌分析中的抽象面子（不含来源信息）。 */
enum class GroupType { CHOW, PUNG, KONG }

data class Group(
    val type: GroupType,
    val tile: Int,
    val concealed: Boolean = true,
    val meldType: MeldType? = null,
) {
    val isChow: Boolean get() = type == GroupType.CHOW
    val isPung: Boolean get() = type == GroupType.PUNG
    val isKong: Boolean get() = type == GroupType.KONG

    val tiles: List<Int>
        get() = when (type) {
            GroupType.CHOW -> listOf(tile, tile + 1, tile + 2)
            GroupType.PUNG -> List(3) { tile }
            GroupType.KONG -> List(4) { tile }
        }

    val isConcealedPungLike: Boolean
        get() = (type == GroupType.PUNG || type == GroupType.KONG) && concealed
}

/** 一组和牌的完整分解：4 个面子 + 1 个将。 */
data class HandShape(
    val pair: Int,
    val groups: List<Group>,
) {
    init {
        require(groups.size == 4) { "HandShape must have 4 groups, got ${groups.size}" }
    }

    val allTiles: List<Int>
        get() = groups.flatMap { it.tiles } + listOf(pair, pair)
}
