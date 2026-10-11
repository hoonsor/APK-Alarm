package com.hoonsor.alarm.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {
    @Test fun morningWithMemo() =
        assertEquals("現在是早上 7 點 15 分。晨跑", TtsEngine.speechText(7, 15, "晨跑", speakTime = true))

    @Test fun onTheHourAfternoon() =
        assertEquals("現在是下午 3 點整。", TtsEngine.speechText(15, 0, "", speakTime = true))

    @Test fun midnight() =
        assertEquals("現在是凌晨 12 點 30 分。吃藥", TtsEngine.speechText(0, 30, "吃藥", speakTime = true))

    @Test fun memoOnly() =
        assertEquals("帶門禁卡", TtsEngine.speechText(8, 0, " 帶門禁卡 ", speakTime = false))
}
