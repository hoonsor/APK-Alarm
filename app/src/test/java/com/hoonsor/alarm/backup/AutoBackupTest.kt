package com.hoonsor.alarm.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.hoonsor.backup.AutoBackup
import java.util.Calendar

/** 共用模組 android-auto-backup 的純邏輯（照抄模組 tests/AutoBackupTest.kt，名稱換成本 APP） */
class AutoBackupTest {
    @Test fun fileName() {
        val c = Calendar.getInstance().apply { set(2026, 10, 10, 9, 5) }
        assertEquals("HoonsorAlarm-20261110-0905.zip", AutoBackup.fileName("HoonsorAlarm", "zip", c.time))
    }

    @Test fun onlyOwnFiles() {
        assertTrue(AutoBackup.isBackupName("HoonsorAlarm-20261110-0905.zip", "HoonsorAlarm", "zip"))
        assertTrue(AutoBackup.isBackupName("HoonsorAlarm-20261110-0905 (1).zip", "HoonsorAlarm", "zip"))
        assertFalse(AutoBackup.isBackupName("HoonsorAlarm-LAB-20261110-0905.zip", "HoonsorAlarm", "zip"))
        assertFalse(AutoBackup.isBackupName("HoonsorAlarm-20261110-0905.json", "HoonsorAlarm", "zip"))
        // 手動「儲存到檔案」的舊檔名格式不會被自動備份刪掉
        assertFalse(AutoBackup.isBackupName("HoonsorAlarm-backup-20261110-0905.zip", "HoonsorAlarm", "zip"))
        assertFalse(AutoBackup.isBackupName("我的筆記.zip", "HoonsorAlarm", "zip"))
    }

    @Test fun keepNewest() {
        val names = (1..9).map { "A-2026110$it-1200.zip" } + "notes.txt"
        val del = AutoBackup.toDelete(names, "A", "zip", 7)
        assertEquals(listOf("A-20261102-1200.zip", "A-20261101-1200.zip"), del)
        assertTrue(AutoBackup.toDelete(names.take(7), "A", "zip", 7).isEmpty())
    }
}

class HoonsorAlarmBackupSourceTest {
    private val s = HoonsorAlarmBackupSource()

    @Test fun nameAndType() {
        assertEquals("HoonsorAlarm", s.appName())
        assertEquals("zip", s.extension())
        assertEquals("application/zip", s.mimeType())
    }

    @Test fun folder() {
        assertEquals("Documents/HoonsorBackup/HoonsorAlarm/", AutoBackup.folder(s))
    }

    @Test fun appNameIsSafeForFileNames() {
        assertTrue(s.appName().matches(Regex("[A-Za-z0-9-]+")))
    }
}
