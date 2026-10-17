package com.hoonsor.alarm.update

/**
 * 比較 GitHub Release tag（例如 v0.11.0）與 App 的 versionName。
 * 純 Kotlin、不依賴 Android，方便單元測試與複製到其他 APP。
 */
object VersionCompare {

    /** 解析後的版本：數字部分與是否帶有預先發行後綴（-beta、-lab 等） */
    data class Version(val numbers: List<Int>, val preRelease: Boolean)

    /** 解析 "v1.2.3"、"1.2"、"1.2.3-lab"；格式不對回傳 null */
    fun parse(raw: String?): Version? {
        val s = raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        if (s.isEmpty()) return null
        val core = s.substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return Version(numbers, preRelease = s.contains('-'))
    }

    /** 比較大小：正數代表 a 較新，0 代表相同 */
    fun compare(a: Version, b: Version): Int {
        val n = maxOf(a.numbers.size, b.numbers.size)
        for (i in 0 until n) {
            val d = a.numbers.getOrElse(i) { 0 } - b.numbers.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        // 數字相同時，正式版比預先發行版新（1.0.0 > 1.0.0-beta）
        return when {
            a.preRelease == b.preRelease -> 0
            a.preRelease -> -1
            else -> 1
        }
    }

    /** remote 是否比 local 新；任一方解析失敗一律視為「不需要更新」 */
    fun isNewer(remote: String?, local: String?): Boolean {
        val r = parse(remote) ?: return false
        val l = parse(local) ?: return false
        return compare(r, l) > 0
    }
}
