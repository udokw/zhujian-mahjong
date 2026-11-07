package mahjong.core.engine

/**
 * 可复现的伪随机数生成器（xorshift64*）。
 * 纯 Kotlin，无平台依赖，保证同一 seed 在不同平台上产生同一牌墙。
 */
class Rng(seed: Long) {
    private var state: Long = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed

    fun nextLong(): Long {
        var x = state
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        state = x
        return x * -0x61c8864680b583ebL
    }

    /** [0, bound) 内的非负整数。 */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        val value = nextLong() ushr 1
        return (value % bound).toInt()
    }

    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    fun <T> shuffle(list: MutableList<T>) {
        for (i in list.lastIndex downTo 1) {
            val j = nextInt(i + 1)
            val tmp = list[i]
            list[i] = list[j]
            list[j] = tmp
        }
    }
}
