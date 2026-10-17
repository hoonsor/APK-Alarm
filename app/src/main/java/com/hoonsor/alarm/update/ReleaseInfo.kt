package com.hoonsor.alarm.update

import org.json.JSONObject

/** GitHub Release 中 App 內更新需要的資訊 */
data class ReleaseInfo(
    /** 例如 "v0.11.0" */
    val tag: String,
    /** 更新說明（Release 內文） */
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    /** APK 位元組數；未知時為 0 */
    val apkSize: Long,
    /** Release 網頁，下載失敗時可讓使用者改用瀏覽器 */
    val pageUrl: String,
) {
    val version: String get() = tag.removePrefix("v").removePrefix("V")

    companion object {
        /**
         * 解析 GET /repos/{owner}/{repo}/releases/latest 的回應。
         * 草稿、預先發行版、或沒有 APK 附件時回傳 null。
         */
        fun parse(json: String, apkPrefix: String): ReleaseInfo? {
            val o = JSONObject(json)
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
            val tag = o.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            val apks = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            // 優先選本 App 命名的 APK，避免同一個 Release 有多個 APK 時抓錯
            val apk = apks.firstOrNull { it.optString("name").startsWith(apkPrefix) } ?: apks.firstOrNull() ?: return null
            val url = apk.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: return null
            return ReleaseInfo(
                tag = tag,
                notes = if (o.isNull("body")) "" else o.optString("body").trim(),
                apkName = apk.optString("name"),
                apkUrl = url,
                apkSize = apk.optLong("size", 0L),
                pageUrl = o.optString("html_url"),
            )
        }
    }
}
