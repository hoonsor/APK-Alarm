package com.hoonsor.alarm.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WheelMathTest {
    private val n = 60

    @Test fun cyclicStartsInTheMiddleLap() {
        val i = WheelMath.initialIndex(25, n, cyclic = true)
        assertEquals(25, WheelMath.offsetAt(i, n, cyclic = true))
        assertEquals(WheelMath.LAPS / 2 * n + 25, i)
    }

    /** 00 上面是 59、59 下面是 00 */
    @Test fun wrapsAround() {
        val zero = WheelMath.initialIndex(0, n, cyclic = true)
        assertEquals(59, WheelMath.offsetAt(zero - 1, n, cyclic = true))
        assertEquals(0, WheelMath.offsetAt(zero + 60, n, cyclic = true))
    }

    /** 外部從 59 改成 00（例如 +1 秒進位）：往下一格，不會倒捲 59 格 */
    @Test fun nearestTakesShortWay() {
        val at59 = WheelMath.initialIndex(59, n, cyclic = true)
        assertEquals(at59 + 1, WheelMath.nearestIndex(at59, 0, n, cyclic = true))
        val at0 = WheelMath.initialIndex(0, n, cyclic = true)
        assertEquals(at0 - 1, WheelMath.nearestIndex(at0, 59, n, cyclic = true))
        assertEquals(at0 + 30, WheelMath.nearestIndex(at0, 30, n, cyclic = true))
    }

    @Test fun nonCyclicUnchanged() {
        assertEquals(100, WheelMath.itemCount(100, cyclic = false))
        assertEquals(7, WheelMath.initialIndex(7, 100, cyclic = false))
        assertEquals(99, WheelMath.offsetAt(150, 100, cyclic = false))
        assertEquals(42, WheelMath.nearestIndex(3, 42, 100, cyclic = false))
    }
}
