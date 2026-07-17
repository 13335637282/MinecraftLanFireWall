import org.fusesource.jansi.Ansi.ansi

object Tools {

    private val cache = mutableMapOf<Pair<Int, Int>, String>()

    fun colorGradient(value: Int, maxValue: Int): String {
        val key = value to maxValue
        cache[key]?.let { return it }

        // 计算前景色 (使用 if-else 避免 nullable 类型)
        val (fgR, fgG, fgB) = if (value == 0) {
            Triple(140, 140, 140)
        } else if (value > maxValue) {
            Triple(255, 0, 0)
        } else {
            val hue = 180f - (value.toFloat() / maxValue) * 180f
            hslToRgb(hue / 360f)
        }

        val bold = value > maxValue

        // 使用 Jansi 构建 ANSI 字符串
        var ansi = ansi()
            .fgRgb(fgR, fgG, fgB)   // 前景色
            .a(value.toString())    // 输出数字

        if (bold) {
            ansi = ansi.bold()
        }
        ansi = ansi.reset()

        val result = ansi.toString()

        // 缓存管理
        if (cache.size >= 1024) {
            val keys = cache.keys.toList()
            for (k in keys.take(cache.size / 2)) {
                cache.remove(k)
            }
        }
        cache[key] = result
        return result
    }

    /**
     * HSL → RGB 转换（h, s, l 均为 0..1）
     */
    private fun hslToRgb(h: Float, s: Float=1.0f, l: Float=0.5f): Triple<Int, Int, Int> {
        if (s == 0f) {
            val gray = (l * 255).toInt()
            return Triple(gray, gray, gray)
        }
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h * 6f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 1f/6f -> Triple(c, x, 0f)
            h < 2f/6f -> Triple(x, c, 0f)
            h < 3f/6f -> Triple(0f, c, x)
            h < 4f/6f -> Triple(0f, x, c)
            h < 5f/6f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Triple(
            ((r + m) * 255).toInt(),
            ((g + m) * 255).toInt(),
            ((b + m) * 255).toInt()
        )
    }

    /**
     * 将 Minecraft 样式代码转换为 ANSI 转义序列（24 位真彩色 + 样式）
     * @param text 原始文本（含样式码）
     * @param colorPrefix 样式前缀符，默认为 '§'
     * @return 带 ANSI 转义码的字符串
     */
    fun parseMcStyle(text: String, colorPrefix: Char = '§'): String {
        if (text.isEmpty()) return ""
        if (colorPrefix !in text) return text

        // ---------- 颜色映射 ----------
        // 标准色码 (0-9, a-f) -> RGB 整数值
        val stdColors = mapOf(
            '0' to 0x000000, '1' to 0x0000AA, '2' to 0x00AA00, '3' to 0x00AAAA,
            '4' to 0xAA0000, '5' to 0xAA00AA, '6' to 0xFFAA00, '7' to 0xAAAAAA,
            '8' to 0x555555, '9' to 0x5555FF, 'a' to 0x55FF55, 'b' to 0x55FFFF,
            'c' to 0xFF5555, 'd' to 0xFF55FF, 'e' to 0xFFFF55, 'f' to 0xFFFFFF
        )

        // 扩展色 (Bedrock g-w)
        val extColors = mapOf(
            'g' to 0xDDD605, 'h' to 0xE3D4D1, 'i' to 0xCECACA, 'j' to 0x443A3B,
            'm' to 0x971607, 'n' to 0xB4684D, 'p' to 0xDEB12D, 'q' to 0x47A036,
            's' to 0x2CBAA8, 't' to 0x21497B, 'u' to 0x9A5CC6, 'v' to 0xEB7114,
            'w' to 0x8CB3FF
        )

        // 样式码 -> ANSI SGR 代码
        val styles = mapOf(
            'l' to 1,   // 粗体
            'o' to 3,   // 斜体
            'n' to 4,   // 下划线
            'k' to 8,   // 闪烁（模糊）
            'm' to 9    // 删除线
        )

        // 辅助：将 RGB 整数值转为 ANSI 24 位前景色序列
        fun colorAnsi(rgb: Int): String {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            return "\u001B[38;2;${r};${g};${b}m"
        }

        val builder = StringBuilder()
        val maxIdx = text.length - 1
        var i = 0

        while (i <= maxIdx) {
            val ch = text[i]
            // 检查是否为前缀符且后面还有字符
            if (ch == colorPrefix && i < maxIdx) {
                when (val next = text[i + 1]) {
                    in stdColors -> {
                        builder.append(colorAnsi(stdColors[next]!!))
                        i += 2
                    }
                    // ----- 扩展色 (g-w) 注意排除 n（与样式冲突）-----
                    in extColors -> {
                        builder.append(colorAnsi(extColors[next]!!))
                        i += 2
                    }
                    // ----- 样式码 (l, o, n, m, k) -----
                    in styles -> {
                        builder.append("\u001B[${styles[next]}m")
                        i += 2
                    }
                    // ----- 重置 §r -----
                    'r' -> {
                        builder.append("\u001B[0m")  // 重置所有样式和颜色
                        i += 2
                    }
                    // ----- 真彩色 §x§R§R§G§G§B§B (共 14 个字符) -----
                    'x' if i + 13 <= maxIdx -> {
                        // 提取 RRRR GGGG BBBB 的每两个字符
                        val hexStr = buildString {
                            for (j in 0..<6) {
                                append(text[i + 3 + j * 2])
                            }
                        }
                        try {
                            val rgb = hexStr.toInt(16)
                            builder.append(colorAnsi(rgb))
                            i += 14   // 跳过 §x 和 12 个 hex 字符
                        } catch (_: NumberFormatException) {
                            // 非法 hex，原样输出当前字符
                            builder.append(ch)
                            i++
                        }
                    }
                    // ----- 未知码，原样输出 -----
                    else -> {
                        builder.append(ch)
                        i++
                    }
                }
            } else {
                // 普通字符直接追加
                builder.append(ch)
                i++
            }
        }
        builder.append("\u001B[0m")

        // 最后可选的：不在尾部添加重置，因为可能希望后续保持样式（但通常样式应在文本末尾自然结束）
        // 原 Python 版本没有额外添加重置，而是让尾部样式保持（如果有开放标签未关闭，但 §r 会关闭）
        // 这里我们也不额外添加重置，符合原逻辑
        return builder.toString()
    }
}