import kotlin.collections.ArrayList

/**
 * 滑动窗口计数器，用于统计一段时间内的触发次数
 * @param maxRecordTime 最大记录时间（秒），超出此时间的记录将被自动清理
 */
class PPTCounter(
    private val maxRecordTime: Double = 60.0
) {
    // 存储有序的时间戳（秒）
    private val records = ArrayList<Double>()

    // 第一次触发的时间（可能为 null）
    var firstTrig: Double? = null
        private set

    /**
     * 清理过期记录（内部方法）
     * 移除所有早于 (currentTime - maxRecordTime) 的时间戳
     */
    private fun cleanExpired(currentTime: Double? = null) {
        val now = currentTime ?: (System.currentTimeMillis() / 1000.0)
        val expireTime = now - maxRecordTime

        // 使用二分查找找到第一个 > expireTime 的索引（类似 bisect_right）
        var idx = records.binarySearch(expireTime)
        if (idx < 0) {
            // 未找到，取插入点（即第一个大于 expireTime 的位置）
            idx = -idx - 1
        } else {
            // 找到相等值，向后跳过所有相等的（因为 bisect_right 要求严格大于）
            while (idx < records.size && records[idx] == expireTime) {
                idx++
            }
        }

        // 删除索引之前的所有元素（前缀）
        if (idx > 0) {
            records.subList(0, idx).clear()
        }
    }

    /**
     * 记录一次触发事件（当前时间）
     */
    fun trig(currentTime: Double? = null) {
        val now = currentTime ?: (System.currentTimeMillis() / 1000.0)
        if (firstTrig == null) {
            firstTrig = now
        }
        records.add(now)
        cleanExpired(now)
    }

    /**
     * 获取在过去 customTime 秒内的触发次数（当前时间之前的滑动窗口）
     * @param customTime 统计时间范围（秒），必须 > 0
     * @param currentTime 可指定的当前时间（默认自动获取）
     * @return 范围内的触发次数
     */
    fun getPerTime(customTime: Double, currentTime: Double? = null): Int {
        if (customTime <= 0.0) return 0
        val now = currentTime ?: (System.currentTimeMillis() / 1000.0)
        cleanExpired(now)

        val startTime = now - customTime
        // 使用二分查找找到第一个 >= startTime 的索引（类似 bisect_left）
        var idx = records.binarySearch(startTime)
        if (idx < 0) {
            idx = -idx - 1  // 插入点（第一个大于 startTime）
        } else {
            // 找到相等值，向前移动到第一个相等的（因为 bisect_left 要求 >=）
            while (idx > 0 && records[idx - 1] == startTime) {
                idx--
            }
        }
        return records.size - idx
    }

    /**
     * 清空所有记录，重置 firstTrig
     */
    fun clear() {
        records.clear()
        firstTrig = null
    }
}