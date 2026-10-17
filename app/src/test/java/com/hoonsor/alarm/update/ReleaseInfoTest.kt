package com.hoonsor.alarm.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseInfoTest {

    private fun json(
        tag: String = "v0.11.0",
        prerelease: Boolean = false,
        body: String = "\"- 新增 App 內更新\"",
        assets: String = """[
            {"name": "other.txt", "size": 10, "browser_download_url": "https://example.com/other.txt"},
            {"name": "HoonsorAlarm-v0.11.0.apk", "size": 12345, "browser_download_url": "https://github.com/hoonsor/APK-Alarm/releases/download/v0.11.0/HoonsorAlarm-v0.11.0.apk"}
        ]""",
    ) = """{
        "tag_name": "$tag", "draft": false, "prerelease": $prerelease, "body": $body,
        "html_url": "https://github.com/hoonsor/APK-Alarm/releases/tag/$tag",
        "assets": $assets
    }"""

    @Test fun 解析正式版() {
        val r = ReleaseInfo.parse(json(), "HoonsorAlarm-")!!
        assertEquals("v0.11.0", r.tag)
        assertEquals("0.11.0", r.version)
        assertEquals("- 新增 App 內更新", r.notes)
        assertEquals("HoonsorAlarm-v0.11.0.apk", r.apkName)
        assertEquals(12345L, r.apkSize)
    }

    @Test fun 預先發行版不算() = assertNull(ReleaseInfo.parse(json(prerelease = true), "HoonsorAlarm-"))

    @Test fun 沒有APK附件時回傳null() = assertNull(ReleaseInfo.parse(json(assets = "[]"), "HoonsorAlarm-"))

    @Test fun 內文為null時視為空字串() = assertEquals("", ReleaseInfo.parse(json(body = "null"), "HoonsorAlarm-")!!.notes)

    @Test fun 優先選本App命名的APK() {
        val assets = """[
            {"name": "VoiceHub-v1.apk", "size": 1, "browser_download_url": "https://example.com/VoiceHub-v1.apk"},
            {"name": "HoonsorAlarm-v0.11.0.apk", "size": 2, "browser_download_url": "https://example.com/HoonsorAlarm-v0.11.0.apk"}
        ]"""
        assertEquals("HoonsorAlarm-v0.11.0.apk", ReleaseInfo.parse(json(assets = assets), "HoonsorAlarm-")!!.apkName)
    }

    @Test fun 非HTTPS下載網址不接受() {
        val assets = """[{"name": "HoonsorAlarm-v0.11.0.apk", "size": 2, "browser_download_url": "http://example.com/a.apk"}]"""
        assertNull(ReleaseInfo.parse(json(assets = assets), "HoonsorAlarm-"))
    }
}
