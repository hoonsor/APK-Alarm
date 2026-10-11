package com.hoonsor.alarm.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import java.io.File

/** 備份檔清單中的一筆（手機或雲端） */
data class BackupEntry(val name: String, val size: Long, val time: Long, val ref: String)

/**
 * 手機本機備份：Android 10 以上存到「下載/HoonsorAlarm」（使用者在檔案管理員看得到，不需要儲存權限）；
 * Android 8–9 存到 App 專屬資料夾（Android/data/com.hoonsor.alarm/files/backups）。
 */
object LocalBackupStore {
    private const val SUB = "HoonsorAlarm"
    private val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + SUB + "/"

    fun locationLabel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "下載/$SUB" else "Android/data/com.hoonsor.alarm/files/backups"

    fun save(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, BackupManager.MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("無法在「下載」資料夾建立檔案")
            try {
                resolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("無法寫入備份檔")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            val dir = legacyDir(context)
            file.copyTo(File(dir, file.name), overwrite = true)
        }
    }

    /** 最新的在前 */
    fun list(context: Context): List<BackupEntry> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val out = mutableListOf<BackupEntry>()
            val projection = arrayOf(
                BaseColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
            )
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(relativePath),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!BackupManager.isBackupName(name)) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                    out += BackupEntry(name, c.getLong(2), c.getLong(3) * 1000, uri.toString())
                }
            }
            return out.sortedByDescending { it.name }
        }
        return legacyDir(context).listFiles().orEmpty()
            .filter { BackupManager.isBackupName(it.name) }
            .map { BackupEntry(it.name, it.length(), it.lastModified(), Uri.fromFile(it).toString()) }
            .sortedByDescending { it.name }
    }

    /** 只保留最近 keep 份（只會刪到本 App 建立的備份檔） */
    fun prune(context: Context, keep: Int) {
        list(context).drop(keep).forEach { e ->
            runCatching {
                val uri = Uri.parse(e.ref)
                if (uri.scheme == "file") File(uri.path!!).delete() else context.contentResolver.delete(uri, null, null)
            }
        }
    }

    private fun legacyDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "backups").apply { mkdirs() }
}
