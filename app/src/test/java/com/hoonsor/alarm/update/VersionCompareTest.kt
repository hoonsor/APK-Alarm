package com.hoonsor.alarm.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    @Test fun 較新的修訂號() = assertTrue(VersionCompare.isNewer("v0.10.5", "0.10.4"))

    @Test fun 次版號以數字比較不是字串比較() = assertTrue(VersionCompare.isNewer("v0.10.0", "0.9.9"))

    @Test fun 主版號較新() = assertTrue(VersionCompare.isNewer("v1.0.0", "0.99.99"))

    @Test fun 相同版本不需更新() = assertFalse(VersionCompare.isNewer("v0.11.0", "0.11.0"))

    @Test fun 較舊版本不需更新() = assertFalse(VersionCompare.isNewer("v0.10.3", "0.10.4"))

    @Test fun 位數不同時補零比較() {
        assertFalse(VersionCompare.isNewer("v1.2", "1.2.0"))
        assertTrue(VersionCompare.isNewer("v1.2.1", "1.2"))
        assertTrue(VersionCompare.isNewer("v1.3", "1.2.9"))
    }

    @Test fun 大寫V與空白也能解析() = assertTrue(VersionCompare.isNewer(" V0.12.0 ", "0.11.0"))

    @Test fun 正式版比同號預先發行版新() {
        assertTrue(VersionCompare.isNewer("v0.11.0", "0.11.0-lab"))
        assertFalse(VersionCompare.isNewer("v0.11.0-beta", "0.11.0"))
        assertFalse(VersionCompare.isNewer("v0.11.0-beta", "0.11.0-lab"))
    }

    @Test fun 預先發行版仍以數字優先() = assertTrue(VersionCompare.isNewer("v0.12.0-beta", "0.11.0"))

    @Test fun 無法解析時一律不更新() {
        assertFalse(VersionCompare.isNewer("latest", "0.11.0"))
        assertFalse(VersionCompare.isNewer("", "0.11.0"))
        assertFalse(VersionCompare.isNewer(null, "0.11.0"))
        assertFalse(VersionCompare.isNewer("v1.0.0", null))
        assertFalse(VersionCompare.isNewer("v1..0", "0.1.0"))
        assertFalse(VersionCompare.isNewer("v1.a.0", "0.1.0"))
    }

    @Test fun 解析結果() {
        assertEquals(VersionCompare.Version(listOf(0, 11, 0), false), VersionCompare.parse("v0.11.0"))
        assertEquals(VersionCompare.Version(listOf(1, 2, 3), true), VersionCompare.parse("1.2.3-lab"))
        assertNotNull(VersionCompare.parse("2"))
        assertNull(VersionCompare.parse("v"))
    }
}
