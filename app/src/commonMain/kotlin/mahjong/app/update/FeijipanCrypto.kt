package mahjong.app.update

/**
 * 飞盘接口参数加密：AES-128-ECB + PKCS7，密钥固定为 ASCII "dingHao-disk-app"，输出大写 hex。
 *
 * 纯 Kotlin 实现，commonMain 两端共用。S 盒与轮常量都按 GF(2^8) 现算而不是抄 256 项常量表：
 * 手抄错一个字节就会让接口一律返回空列表，这种错误很难从现象反推。
 */
object FeijipanCrypto {
    private const val KEY = "dingHao-disk-app"

    /** GF(2^8) 乘法，既用于生成 S 盒，也用于列混合。 */
    private fun gmul(a0: Int, b0: Int): Int {
        var a = a0
        var b = b0
        var p = 0
        repeat(8) {
            if (b and 1 != 0) p = p xor a
            val hi = a and 0x80
            a = (a shl 1) and 0xff
            if (hi != 0) a = a xor 0x1b
            b = b shr 1
        }
        return p
    }

    private fun gpow(x: Int, e: Int): Int {
        var result = 1
        var base = x
        var exp = e
        while (exp > 0) {
            if (exp and 1 == 1) result = gmul(result, base)
            base = gmul(base, base)
            exp = exp shr 1
        }
        return result
    }

    private val SBOX: IntArray = IntArray(256) { i ->
        // 0 的乘法逆元按定义取 0；其余取 x^254。
        val inv = if (i == 0) 0 else gpow(i, 254)
        var s = inv
        var x = inv
        repeat(4) {
            x = ((x shl 1) or (x shr 7)) and 0xff
            s = s xor x
        }
        (s xor 0x63) and 0xff
    }

    private val RCON: IntArray = IntArray(10).also { rcon ->
        var v = 1
        for (i in 0 until 10) {
            rcon[i] = v
            v = gmul(v, 2)
        }
    }

    private val ROUND_KEYS: Array<IntArray> = expandKey(KEY.encodeToByteArray())

    private fun expandKey(key: ByteArray): Array<IntArray> {
        val w = Array(44) { IntArray(4) }
        for (i in 0 until 4) {
            for (j in 0 until 4) w[i][j] = key[i * 4 + j].toInt() and 0xff
        }
        for (i in 4 until 44) {
            var t = w[i - 1].copyOf()
            if (i % 4 == 0) {
                t = intArrayOf(
                    SBOX[t[1]] xor RCON[i / 4 - 1],
                    SBOX[t[2]],
                    SBOX[t[3]],
                    SBOX[t[0]],
                )
            }
            for (j in 0 until 4) w[i][j] = w[i - 4][j] xor t[j]
        }
        return Array(11) { round ->
            IntArray(16) { k -> w[round * 4 + k / 4][k % 4] }
        }
    }

    private fun encryptBlock(state: IntArray): IntArray {
        val s = IntArray(16)
        for (i in 0 until 16) s[i] = state[i] xor ROUND_KEYS[0][i]

        for (round in 1..10) {
            for (i in 0 until 16) s[i] = SBOX[s[i]]

            val shifted = IntArray(16)
            for (row in 0 until 4) {
                for (col in 0 until 4) {
                    // 状态按列存放：s[col * 4 + row]；ShiftRows 把第 row 行左移 row 格。
                    shifted[col * 4 + row] = s[((col + row) and 3) * 4 + row]
                }
            }
            for (i in 0 until 16) s[i] = shifted[i]

            if (round != 10) {
                for (col in 0 until 4) {
                    val a0 = s[col * 4]
                    val a1 = s[col * 4 + 1]
                    val a2 = s[col * 4 + 2]
                    val a3 = s[col * 4 + 3]
                    s[col * 4] = gmul(a0, 2) xor gmul(a1, 3) xor a2 xor a3
                    s[col * 4 + 1] = a0 xor gmul(a1, 2) xor gmul(a2, 3) xor a3
                    s[col * 4 + 2] = a0 xor a1 xor gmul(a2, 2) xor gmul(a3, 3)
                    s[col * 4 + 3] = gmul(a0, 3) xor a1 xor a2 xor gmul(a3, 2)
                }
            }

            for (i in 0 until 16) s[i] = s[i] xor ROUND_KEYS[round][i]
        }
        return s
    }

    private const val HEX = "0123456789ABCDEF"

    /** PKCS7 填充后逐块加密，返回大写 hex（接口要求大写）。 */
    fun encryptHex(text: String): String {
        val data = text.encodeToByteArray()
        val pad = 16 - data.size % 16
        val padded = ByteArray(data.size + pad) { i -> if (i < data.size) data[i] else pad.toByte() }

        val out = StringBuilder((data.size + pad) * 2)
        for (offset in padded.indices step 16) {
            val block = IntArray(16) { padded[offset + it].toInt() and 0xff }
            val encrypted = encryptBlock(block)
            for (b in encrypted) {
                out.append(HEX[b shr 4]).append(HEX[b and 0x0f])
            }
        }
        return out.toString()
    }
}
