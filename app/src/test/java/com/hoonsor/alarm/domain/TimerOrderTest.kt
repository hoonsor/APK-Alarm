package com.hoonsor.alarm.domain

import com.hoonsor.alarm.data.TimerEntity
import com.hoonsor.alarm.data.TimerState
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerOrderTest {
    private val m = 60_000L

    @Test fun runningBeforePausedBeforeNotStarted() {
        val notStarted = TimerEntity(id = 1, durationMs = 5 * m)
        val paused = TimerEntity(id = 2, durationMs = 30 * m, remainingMs = 20 * m)
        val runningLong = TimerEntity(id = 3, durationMs = 60 * m, state = TimerState.RUNNING)
        val runningShort = TimerEntity(id = 4, durationMs = 10 * m, state = TimerState.RUNNING)
        val ringing = TimerEntity(id = 5, durationMs = 3 * m, state = TimerState.RINGING, remainingMs = 0)
        val remaining = mapOf(1L to 5 * m, 2L to 20 * m, 3L to 50 * m, 4L to 8 * m, 5L to 0L)
        val sorted = listOf(notStarted, paused, runningLong, runningShort, ringing)
            .sortedWith(compareBy({ TimerOrder.stateRank(it, remaining.getValue(it.id)) }, { remaining.getValue(it.id) }))
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), sorted.map { it.id })
    }
}
