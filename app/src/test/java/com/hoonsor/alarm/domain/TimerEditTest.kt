package com.hoonsor.alarm.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerEditTest {
    private val h = 3_600_000L
    private val m = 60_000L
    private val s = 1_000L

    /** 9 小時的計時器跑了 30 秒後打開編輯，滾輪改成 8:50:00，20 秒後才按儲存 */
    @Test fun nineHoursToEightFifty() {
        val initial = 8 * h + 59 * m + 30 * s
        val edited = 8 * h + 50 * m
        val delta = TimerEdit.delta(initial, edited)
        assertEquals(-(9 * m + 30 * s), delta)
        val currentAtSave = initial - 20 * s
        // 響鈴時刻＝打開視窗時刻＋8:50:00，所以儲存當下剩下 8:49:40
        assertEquals(8 * h + 49 * m + 40 * s, TimerEdit.shiftedRemaining(currentAtSave, delta))
    }

    @Test fun unchangedKeepsTime() {
        assertEquals(0L, TimerEdit.delta(5 * h + 123, 5 * h + 123))
    }

    @Test fun clampsToRange() {
        assertEquals(1_000L, TimerEdit.shiftedRemaining(30 * s, -10 * m))
        assertEquals(TimerEdit.MAX_MS, TimerEdit.shiftedRemaining(99 * h, 2 * h))
    }
}
