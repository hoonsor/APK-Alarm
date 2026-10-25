package com.hoonsor.alarm.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.hoonsor.alarm.BuildConfig
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.data.AlarmEntity
import com.hoonsor.alarm.data.AlarmGroupEntity
import com.hoonsor.alarm.data.TimerEntity
import com.hoonsor.alarm.data.TimerGroupEntity
import com.hoonsor.alarm.data.TimerState
import com.hoonsor.alarm.data.VignetteShape
import com.hoonsor.alarm.tts.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class ImportMode { MERGE, REPLACE }

data class ImportResult(val alarms: Int, val timers: Int, val groups: Int, val media: Int, val settingsApplied: Boolean)

/**
 * 備份格式：一個 zip 檔
 *  - backup.json：鬧鐘、群組、計時器、計時器群組、設定
 *  - media/images/…、media/sounds/…（選擇「包含圖片與鈴聲」時）
 *
 * 不備份：MiniMax API key 與雲端登入資訊（以手機 Keystore 加密，換手機無法解密）、雲端語音快取（匯入後重新產生）。
 */
object BackupManager {
    private const val TAG = "Backup"
    private const val FORMAT = 1
    private const val JSON_NAME = "backup.json"
    private const val IMG_PREFIX = "media/images/"
    private const val SND_PREFIX = "media/sounds/"
    const val MIME = "application/zip"

    fun suggestedName(now: LocalDateTime = LocalDateTime.now()): String =
        "HoonsorAlarm-backup-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".zip"

    fun isBackupName(name: String) = name.startsWith("HoonsorAlarm-backup-") && name.endsWith(".zip")

    // ---------- 匯出 ----------

    /** 寫到使用者用檔案選擇器挑的位置（可以是手機、Google 雲端硬碟或 Dropbox） */
    suspend fun exportTo(context: Context, uri: Uri, includeMedia: Boolean) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("無法寫入選擇的位置")
        out.use { writeZip(it, includeMedia) }
    }

    /** 產生暫存備份檔（自動備份、上傳雲端用） */
    suspend fun exportToTemp(context: Context, includeMedia: Boolean): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "backup").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, suggestedName())
        file.outputStream().use { writeZip(it, includeMedia) }
        file
    }

    private suspend fun writeZip(out: OutputStream, includeMedia: Boolean) {
        val db = Graph.db
        val alarms = db.alarmDao().getAll()
        val groups = db.groupDao().getAll()
        val timers = db.timerDao().getAll()
        val timerGroups = db.timerGroupDao().getAll()
        val s = Graph.settings

        // 媒體檔：只收 App 內部的檔案（content:// 系統鈴聲換手機也找得到，直接存 URI）
        val media = linkedMapOf<String, File>() // zip 內路徑 → 檔案
        fun mediaRef(path: String?, prefix: String): String? {
            if (path.isNullOrBlank()) return null
            if (!path.startsWith("/")) return path
            val f = File(path)
            if (!includeMedia || !f.exists()) return null
            val entry = prefix + f.name
            media[entry] = f
            return entry
        }

        val json = JSONObject()
            .put("app", "HoonsorAlarm")
            .put("format", FORMAT)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("exportedAt", System.currentTimeMillis())
            .put("includesMedia", includeMedia)
            .put("alarmGroups", JSONArray().apply { groups.forEach { put(groupJson(it.id, it.name, it.enabled, it.expanded, it.sortOrder, it.createdAt, it.colorIndex)) } })
            .put("timerGroups", JSONArray().apply { timerGroups.forEach { put(groupJson(it.id, it.name, it.enabled, it.expanded, it.sortOrder, it.createdAt, it.colorIndex)) } })
            .put("alarms", JSONArray().apply {
                alarms.forEach { a ->
                    put(
                        JSONObject()
                            .put("hour", a.hour).put("minute", a.minute).put("daysMask", a.daysMask)
                            .put("enabled", a.enabled).put("memo", a.memo)
                            .putOpt("image", mediaRef(a.imagePath, IMG_PREFIX))
                            .putOpt("notBefore", a.notBefore)
                            .put("isCopy", a.isCopy).put("createdAt", a.createdAt)
                            .putOpt("groupId", a.groupId)
                            .putOpt("sound", mediaRef(a.soundUri, SND_PREFIX))
                            .putOpt("volume", a.volume)
                            .put("vibrate", a.vibrate).put("speakMemo", a.speakMemo)
                            .put("speakOnceThenRing", a.speakOnceThenRing).put("silent", a.silent)
                            .put("autoDelete", a.autoDelete),
                    )
                }
            })
            .put("timers", JSONArray().apply {
                timers.forEach { t ->
                    put(
                        JSONObject()
                            .put("label", t.label).put("durationMs", t.durationMs)
                            .put("remainingMs", liveRemaining(t)).put("createdAt", t.createdAt)
                            .putOpt("groupId", t.groupId)
                            .put("autoDelete", t.autoDelete),
                    )
                }
            })
            .put(
                "settings",
                JSONObject()
                    .put("vignetteShape", s.vignetteShape.name)
                    .put("vignetteStrength", s.vignetteStrength)
                    .putOpt("defaultImage", mediaRef(s.defaultImagePath, IMG_PREFIX))
                    .putOpt("alarmSound", mediaRef(s.alarmSoundUri, SND_PREFIX))
                    .putOpt("timerSound", mediaRef(s.timerSoundUri, SND_PREFIX))
                    .put("aiBaseUrl", s.aiBaseUrl).put("aiModel", s.aiModel).put("aiEnabled", s.aiEnabled)
                    .put("ttsEngine", s.ttsEngine).put("ttsVoice", s.ttsVoice).put("ttsModel", s.ttsModel)
                    .put("ttsRate", s.ttsRate.toDouble()).put("ttsBoostDb", s.ttsBoostDb).put("ttsSpeakTime", s.ttsSpeakTime)
                    .put("rampSeconds", s.rampSeconds).put("timerSort", s.timerSort).put("lastTimerMs", s.lastTimerMs),
            )

        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_NAME))
            zip.write(json.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            media.forEach { (entry, file) ->
                zip.putNextEntry(ZipEntry(entry))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun groupJson(id: Long, name: String, enabled: Boolean, expanded: Boolean, sortOrder: Int, createdAt: Long, color: Int) =
        JSONObject().put("id", id).put("name", name).put("enabled", enabled).put("expanded", expanded)
            .put("sortOrder", sortOrder).put("createdAt", createdAt).put("colorIndex", color)

    private fun liveRemaining(t: TimerEntity): Long =
        if (t.state == TimerState.RUNNING) {
            t.endAtWall?.let { (it - System.currentTimeMillis()).coerceAtLeast(0) } ?: t.remainingMs
        } else if (t.state == TimerState.PAUSED) t.remainingMs else t.durationMs

    // ---------- 匯入 ----------

    suspend fun importFrom(context: Context, uri: Uri, mode: ImportMode): ImportResult = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: error("無法讀取檔案")
        input.use { importZip(context, it, mode) }
    }

    suspend fun importFile(context: Context, file: File, mode: ImportMode): ImportResult = withContext(Dispatchers.IO) {
        file.inputStream().use { importZip(context, it, mode) }
    }

    private suspend fun importZip(context: Context, input: InputStream, mode: ImportMode): ImportResult {
        // 先把整個 zip 讀進暫存資料夾；全部讀成功才動資料庫，避免檔案損毀時資料被清空
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var jsonText: String? = null
        ZipInputStream(input.buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val name = e.name
                if (!e.isDirectory && !name.contains("..")) {
                    if (name == JSON_NAME) jsonText = zip.readBytes().toString(Charsets.UTF_8)
                    else if (name.startsWith(IMG_PREFIX) || name.startsWith(SND_PREFIX)) {
                        val f = File(staging, name).apply { parentFile?.mkdirs() }
                        f.outputStream().use { zip.copyTo(it) }
                    }
                }
                e = zip.nextEntry
            }
        }
        val json = JSONObject(jsonText ?: error("這不是鬧鐘 App 的備份檔（找不到 backup.json）"))
        if (json.optString("app") != "HoonsorAlarm") error("這不是鬧鐘 App 的備份檔")
        if (json.optInt("format", 0) > FORMAT) error("備份檔版本較新，請先更新 App")

        // 媒體檔複製到 App 內部，回傳新路徑
        var mediaCount = 0
        val copied = mutableMapOf<String, String>()
        fun restoreMedia(ref: String?): String? {
            if (ref.isNullOrBlank()) return null
            if (!ref.startsWith(IMG_PREFIX) && !ref.startsWith(SND_PREFIX)) return ref // content:// 系統鈴聲
            copied[ref]?.let { return it }
            val src = File(staging, ref)
            if (!src.exists()) return null
            val isImage = ref.startsWith(IMG_PREFIX)
            val dir = File(context.filesDir, if (isImage) "images" else "sounds").apply { mkdirs() }
            val base = src.name
            val newName = if (isImage) "${UUID.randomUUID()}.${base.substringAfterLast('.', "webp")}"
            else UUID.randomUUID().toString().take(8) + "__" + base.substringAfter("__")
            val dst = File(dir, newName)
            src.copyTo(dst, overwrite = true)
            mediaCount++
            return dst.absolutePath.also { copied[ref] = it }
        }

        val db = Graph.db
        val alarmDao = db.alarmDao()
        val groupDao = db.groupDao()
        val timerDao = db.timerDao()
        val tgDao = db.timerGroupDao()
        var alarmCount = 0
        var timerCount = 0
        var groupCount = 0
        // 取代前記下舊的媒體檔，匯入後刪掉不再使用的
        val oldAlarms = if (mode == ImportMode.REPLACE) alarmDao.getAll() else emptyList()
        val oldImages = oldAlarms.map { it.imagePath } + Graph.settings.defaultImagePath
        val oldSounds = oldAlarms.map { it.soundUri } + listOf(Graph.settings.alarmSoundUri, Graph.settings.timerSoundUri)

        db.withTransaction {
            if (mode == ImportMode.REPLACE) {
                alarmDao.deleteAll(); groupDao.deleteAll(); timerDao.deleteAll(); tgDao.deleteAll()
            }
            // 群組：合併模式下同名群組直接沿用
            val existingGroups = groupDao.getAll()
            val groupMap = mutableMapOf<Long, Long>()
            json.optJSONArray("alarmGroups").forEachObj { g ->
                val name = g.optString("name").ifBlank { "群組" }
                val same = existingGroups.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }
                groupMap[g.optLong("id")] = same?.id ?: groupDao.insert(
                    AlarmGroupEntity(
                        name = name, enabled = g.optBoolean("enabled", true), expanded = g.optBoolean("expanded", true),
                        sortOrder = g.optInt("sortOrder"), createdAt = g.optLong("createdAt", System.currentTimeMillis()),
                        colorIndex = g.optInt("colorIndex", -1),
                    ),
                ).also { groupCount++ }
            }
            val existingTg = tgDao.getAll()
            val tgMap = mutableMapOf<Long, Long>()
            json.optJSONArray("timerGroups").forEachObj { g ->
                val name = g.optString("name").ifBlank { "群組" }
                val same = existingTg.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }
                tgMap[g.optLong("id")] = same?.id ?: tgDao.insert(
                    TimerGroupEntity(
                        name = name, enabled = g.optBoolean("enabled", true), expanded = g.optBoolean("expanded", true),
                        sortOrder = g.optInt("sortOrder"), createdAt = g.optLong("createdAt", System.currentTimeMillis()),
                        colorIndex = g.optInt("colorIndex", -1),
                    ),
                ).also { groupCount++ }
            }
            json.optJSONArray("alarms").forEachObj { a ->
                val h = a.optInt("hour", -1)
                val m = a.optInt("minute", -1)
                if (h !in 0..23 || m !in 0..59) return@forEachObj
                alarmDao.insert(
                    AlarmEntity(
                        hour = h, minute = m, daysMask = a.optInt("daysMask") and 0x7F,
                        enabled = a.optBoolean("enabled", true), memo = a.optString("memo").take(200),
                        imagePath = restoreMedia(a.optStr("image")),
                        notBefore = if (a.has("notBefore")) a.optLong("notBefore") else null,
                        isCopy = a.optBoolean("isCopy"), createdAt = a.optLong("createdAt", System.currentTimeMillis()),
                        groupId = if (a.has("groupId")) groupMap[a.optLong("groupId")] else null,
                        soundUri = restoreMedia(a.optStr("sound")),
                        volume = if (a.has("volume")) a.optInt("volume").coerceIn(10, 100) else null,
                        vibrate = a.optBoolean("vibrate", true), speakMemo = a.optBoolean("speakMemo"),
                        speakOnceThenRing = a.optBoolean("speakOnceThenRing", true), silent = a.optBoolean("silent"),
                        autoDelete = a.optBoolean("autoDelete"),
                    ),
                )
                alarmCount++
            }
            json.optJSONArray("timers").forEachObj { t ->
                val dur = t.optLong("durationMs")
                if (dur <= 0) return@forEachObj
                // 計時器一律以「暫停」還原，避免匯入當下突然響鈴
                timerDao.insert(
                    TimerEntity(
                        label = t.optString("label"), durationMs = dur, state = TimerState.PAUSED,
                        remainingMs = t.optLong("remainingMs", dur).coerceIn(minOf(1_000L, dur), dur),
                        createdAt = t.optLong("createdAt", System.currentTimeMillis()),
                        groupId = if (t.has("groupId")) tgMap[t.optLong("groupId")] else null,
                        autoDelete = t.optBoolean("autoDelete"),
                    ),
                )
                timerCount++
            }
        }

        // 設定：只有「取代」才套用，合併時保留目前的設定
        var settingsApplied = false
        if (mode == ImportMode.REPLACE) {
            json.optJSONObject("settings")?.let { st ->
                val s = Graph.settings
                runCatching { s.vignetteShape = VignetteShape.valueOf(st.optString("vignetteShape", s.vignetteShape.name)) }
                s.vignetteStrength = st.optInt("vignetteStrength", s.vignetteStrength)
                s.defaultImagePath = restoreMedia(st.optStr("defaultImage"))
                s.alarmSoundUri = restoreMedia(st.optStr("alarmSound"))
                s.timerSoundUri = restoreMedia(st.optStr("timerSound"))
                s.aiBaseUrl = st.optString("aiBaseUrl", s.aiBaseUrl)
                s.aiModel = st.optString("aiModel", s.aiModel)
                s.aiEnabled = st.optBoolean("aiEnabled", s.aiEnabled)
                s.ttsEngine = st.optString("ttsEngine", s.ttsEngine)
                s.ttsVoice = st.optString("ttsVoice", s.ttsVoice)
                s.ttsModel = st.optString("ttsModel", s.ttsModel)
                s.ttsRate = st.optDouble("ttsRate", s.ttsRate.toDouble()).toFloat()
                s.ttsBoostDb = st.optInt("ttsBoostDb", s.ttsBoostDb)
                s.ttsSpeakTime = st.optBoolean("ttsSpeakTime", s.ttsSpeakTime)
                s.rampSeconds = st.optInt("rampSeconds", s.rampSeconds)
                s.timerSort = st.optString("timerSort", s.timerSort)
                s.lastTimerMs = st.optLong("lastTimerMs", s.lastTimerMs)
                settingsApplied = true
            }
        }
        staging.deleteRecursively()

        Graph.scheduler.rescheduleAll("匯入備份")
        Graph.appScope.launch { runCatching { TtsEngine.prepareAll() }.onFailure { Log.w(TAG, "tts prepare failed", it) } }
        if (mode == ImportMode.REPLACE) {
            Graph.appScope.launch { runCatching { Graph.repo.purgeUnusedImages(oldImages); Graph.repo.purgeUnusedSounds(oldSounds) } }
        }
        return ImportResult(alarmCount, timerCount, groupCount, mediaCount, settingsApplied)
    }

    private inline fun JSONArray?.forEachObj(block: (JSONObject) -> Unit) {
        this ?: return
        for (i in 0 until length()) optJSONObject(i)?.let(block)
    }

    private fun JSONObject.optStr(name: String): String? = if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
