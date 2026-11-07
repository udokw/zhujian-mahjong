package mahjong.core.engine

/**
 * 牌墙：头部正常摸牌，尾部补花/杠后补牌/买马。
 * 用双指针实现，摸牌 O(1)，且能准确判断剩余张数。
 */
class Wall(tiles: List<Int>) {
    private val tiles: MutableList<Int> = ArrayList(tiles)
    private var head = 0
    private var tail = tiles.size - 1

    val remaining: Int get() = tail - head + 1
    val isEmpty: Boolean get() = remaining <= 0

    fun drawHead(): Int? {
        if (head > tail) return null
        return tiles[head++]
    }

    fun drawTail(): Int? {
        if (head > tail) return null
        return tiles[tail--]
    }

    /** 买马等需要从牌尾移除但不定为摸牌的牌。 */
    fun removeTail(): Int? = drawTail()

    fun snapshot(): List<Int> = tiles.subList(head, tail + 1).toList()
}
