package com.hoonsor.alarm.domain

import java.time.LocalDate

/**
 * 「下週一」「這週五」「11 月 20 號」這類日期說法的換算（JVM 單元測試覆蓋）。
 * App 內語音解析與語音總控的 Agent API 共用，避免各自算出不同的日期。
 *
 * 週的定義：週一開始、週日結束（台灣行事曆習慣）。
 * - 這週X：本週的星期X（可能已經過了，由呼叫端決定怎麼處理）
 * - 下週X：下一個日曆週的星期X；週六說「下週一」是後天，週一說「下週一」是七天後
 * - 下下週X：再往後一週
 */
object DateWords {

    /** weekOffset：0＝這週、1＝下週、2＝下下週；dayIndex：0＝週一 … 6＝週日 */
    fun weekday(today: LocalDate, weekOffset: Int, dayIndex: Int): LocalDate {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return monday.plusWeeks(weekOffset.toLong()).plusDays(dayIndex.toLong())
    }

    /** 最近一個星期X；includeToday = true 時今天也算 */
    fun upcoming(today: LocalDate, dayIndex: Int, includeToday: Boolean): LocalDate {
        var diff = (dayIndex - (today.dayOfWeek.value - 1) + 7) % 7
        if (diff == 0 && !includeToday) diff = 7
        return today.plusDays(diff.toLong())
    }

    /** 月／日（沒說年份）：今年的這一天已經過了就當明年；日期不存在回傳 null */
    fun monthDay(today: LocalDate, month: Int, day: Int): LocalDate? {
        val thisYear = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        return if (thisYear.isBefore(today)) runCatching { LocalDate.of(today.year + 1, month, day) }.getOrNull() else thisYear
    }

    /** 「今天」「明天」「後天」，其他顯示「11/16（一）」 */
    fun label(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.plusDays(2) -> "後天"
        else -> "${date.monthValue}/${date.dayOfMonth}（${Days.LABELS[date.dayOfWeek.value - 1]}）"
    }

    /** 日期與星期，不用「明天」等相對說法：「11/16（一）」 */
    fun short(date: LocalDate): String = "${date.monthValue}/${date.dayOfMonth}（${Days.LABELS[date.dayOfWeek.value - 1]}）"
}
