package com.hoonsor.alarm.voice

import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * 語料測試：用 voice-corpus.tsv（38 號專案 corpus_factory.py 以 MiniMax 生成＋盲驗）大量測離線 VoiceParser。
 * 只產生報告、不讓建置失敗：通過率是用來追蹤進步的指標，不是門檻。
 * 執行：./gradlew :app:testDebugUnitTest --tests "*VoiceCorpusTest"
 * 報告：app/build/reports/voice-corpus.md
 */
class VoiceCorpusTest {

    private data class Row(val id: String, val type: String, val now: LocalDateTime, val text: String,
                           val hour: Int?, val minute: Int?, val mask: Int?, val offset: Long?, val durationSec: Long?, val memo: String)

    /** tsv 裡「# 標籤修正」開頭的註解，原樣列進報告 */
    private var labelFixes = emptyList<String>()

    private fun rows(): List<Row> {
        val stream = javaClass.classLoader!!.getResourceAsStream("voice-corpus.tsv") ?: return emptyList()
        val raw = stream.bufferedReader(Charsets.UTF_8).readLines()
        labelFixes = raw.filter { it.startsWith("# 標籤修正") }.map { it.removePrefix("# ") }
        val lines = raw.filter { it.isNotBlank() && !it.startsWith("#") }
        return lines.drop(1).map { line ->
            val c = line.split('\t') + List(10) { "" }
            Row(c[0], c[1], LocalDateTime.parse(c[2]), c[3], c[4].toIntOrNull(), c[5].toIntOrNull(),
                c[6].toIntOrNull(), c[7].toLongOrNull(), c[8].toLongOrNull(), c[9])
        }
    }

    /** 回傳失敗原因；null＝通過。提醒文字只做寬鬆比對，另外統計。 */
    private fun check(r: Row, got: VoiceIntent): String? = when (r.type) {
        "Timer" -> when {
            got !is VoiceIntent.Timer -> "類型：預期計時器，得到 ${got.javaClass.simpleName}"
            got.durationMs != r.durationSec!! * 1000 -> "時長：預期 ${r.durationSec} 秒，得到 ${got.durationMs / 1000} 秒"
            else -> null
        }
        else -> when {
            got !is VoiceIntent.Alarm -> "類型：預期鬧鐘，得到 ${got.javaClass.simpleName}" +
                ((got as? VoiceIntent.Unknown)?.let { "（${it.reason}）" } ?: "")
            got.hour != r.hour || got.minute != r.minute -> "時間：預期 %02d:%02d，得到 %02d:%02d".format(r.hour, r.minute, got.hour, got.minute)
            got.daysMask != (r.mask ?: 0) -> "重複：預期 ${r.mask}，得到 ${got.daysMask}"
            got.notBeforeDate != r.offset?.let { r.now.toLocalDate().plusDays(it) } ->
                "日期：預期 ${r.offset?.let { "+$it 天" } ?: "不指定"}，得到 ${got.notBeforeDate ?: "不指定"}"
            else -> null
        }
    }

    private fun memoOk(r: Row, got: VoiceIntent): Boolean {
        if (r.memo.isEmpty()) return true
        val m = (got as? VoiceIntent.Alarm)?.memo ?: (got as? VoiceIntent.Timer)?.memo ?: return false
        return m.contains(r.memo) || r.memo.contains(m) && m.length >= 2
    }

    @Test fun corpus() {
        val all = rows()
        if (all.isEmpty()) { println("voice-corpus.tsv 不存在，略過"); return }
        val fails = mutableListOf<Triple<Row, String, VoiceIntent>>()
        var memoMiss = 0
        val byReason = sortedMapOf<String, Int>()
        for (r in all) {
            val got = VoiceParser.parse(r.text, r.now)
            val why = check(r, got)
            if (why != null) {
                fails += Triple(r, why, got)
                byReason.merge(why.substringBefore('：'), 1, Int::plus)
            } else if (!memoOk(r, got)) memoMiss++
        }
        val pass = all.size - fails.size
        val report = buildString {
            appendLine("# 語音語料測試報告（離線 VoiceParser）")
            appendLine()
            appendLine("- 句數：${all.size}　通過：$pass（${pass * 100 / all.size}%）　失敗：${fails.size}")
            appendLine("- 通過的句子中，提醒文字和預期不同：$memoMiss 句（不算失敗，只供參考）")
            appendLine("- 鬧鐘：${all.count { it.type == "Alarm" }} 句，計時器：${all.count { it.type == "Timer" }} 句")
            labelFixes.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## 失敗原因分布")
            byReason.forEach { (k, v) -> appendLine("- $k：$v") }
            appendLine()
            appendLine("## 失敗句子")
            appendLine("| id | 句子 | 原因 |")
            appendLine("|---|---|---|")
            fails.forEach { (r, why, _) -> appendLine("| ${r.id} | ${r.text.replace("|", "／")} | $why |") }
        }
        val out = File("build/reports/voice-corpus.md")
        out.parentFile.mkdirs()
        out.writeText(report, Charsets.UTF_8)
        println("語料測試：${all.size} 句，通過 $pass（${pass * 100 / all.size}%），報告 ${out.absolutePath}")
    }
}
