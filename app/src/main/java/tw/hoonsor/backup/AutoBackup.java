package tw.hoonsor.backup;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 每日自動備份（hoonsor-kit 模組 android-auto-backup）。
 *
 * 每天把 APP 的備份檔寫到手機的 Documents/HoonsorBackup/&lt;APP&gt;/，再由 Dropsync 同步到 Dropbox。
 * 只用 Android 內建 API（JobScheduler、MediaStore），不需要任何套件、不需要儲存權限；Java 寫成，
 * Kotlin 專案與非 Gradle 專案（build.sh）都能直接放進去。需要 Android 10（API 29）以上。
 *
 * 使用方式：
 * 1. 寫一個 {@link Source} 實作（要有無參數建構子），在 AndroidManifest 的 &lt;application&gt; 裡登記：
 *    &lt;meta-data android:name="tw.hoonsor.backup.SOURCE" android:value="完整類別名稱" /&gt;
 *    同時登記 {@link BackupJobService}（見 README）。
 * 2. 設定頁放開關：{@link #setEnabled}、{@link #isEnabled}、{@link #lastSuccess}、{@link #lastError}、{@link #runNowAsync}。
 * 3. APP 啟動時呼叫 {@link #ensureScheduled}（開著才排，已排好不會重排）。
 *
 * 規則：內容和上一次完全一樣就不另存一份（不會讓 Dropbox 塞滿相同檔案）；每個 APP 只留最近 {@link #KEEP} 份。
 * 金鑰（API key、Keystore 加密的資料）不要放進備份：換手機後本來就解不開，也不該離開手機。
 */
public final class AutoBackup {
    private AutoBackup() {}

    /** 每個 APP 實作一個：把備份內容寫進 out（通常是 zip 或 json） */
    public interface Source {
        /** 檔名前綴，只用英數字與 -，例如 "HoonsorAlarm"；資料夾也用這個名稱 */
        String appName();
        /** 副檔名，不含點，例如 "zip"、"json" */
        String extension();
        /** MIME，例如 "application/zip" */
        String mimeType();
        /** 在背景執行緒呼叫；丟出例外就算這次失敗 */
        void write(Context context, OutputStream out) throws Exception;
    }

    public static final int KEEP = 7;
    public static final String ROOT = "Documents/HoonsorBackup/";
    static final int JOB_ID = 0x4B4B0001;
    private static final String PREFS = "hoonsor_autobackup";
    private static final String META_SOURCE = "tw.hoonsor.backup.SOURCE";

    // ---------------- 設定 ----------------

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 預設開啟：新安裝的 APP 不用設定就會每天備份 */
    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean("enabled", true);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("enabled", on).apply();
        if (on) schedule(c, true); else cancel(c);
    }

    /** 上次成功的時間（毫秒）；沒有成功過回 0 */
    public static long lastSuccess(Context c) {
        return prefs(c).getLong("lastSuccess", 0L);
    }

    /** 上次失敗的原因；上次成功就回 null */
    public static String lastError(Context c) {
        return prefs(c).getString("lastError", null);
    }

    /** 上次寫出的檔名（內容沒變時是沿用的那一份） */
    public static String lastFile(Context c) {
        return prefs(c).getString("lastFile", null);
    }

    /** 給設定頁顯示的一行狀態 */
    public static String statusText(Context c) {
        if (Build.VERSION.SDK_INT < 29) return "需要 Android 10 以上才能自動備份";
        if (!isEnabled(c)) return "自動備份已關閉";
        String err = lastError(c);
        long ok = lastSuccess(c);
        String when = ok == 0 ? "還沒備份過" : "上次備份：" + new SimpleDateFormat("M/d HH:mm", Locale.TAIWAN).format(new Date(ok));
        return err == null ? when : when + "；上次失敗：" + err;
    }

    /** 資料夾在手機上的位置，給設定頁說明用 */
    public static String folder(Source s) {
        return ROOT + s.appName() + "/";
    }

    // ---------------- 排程 ----------------

    /** APP 啟動時呼叫：開著而且還沒排就排一個每日工作 */
    public static void ensureScheduled(Context c) {
        if (isEnabled(c)) schedule(c, false);
    }

    static void schedule(Context c, boolean replace) {
        if (Build.VERSION.SDK_INT < 29) return;
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!replace && js.getPendingJob(JOB_ID) != null) return;
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, BackupJobService.class))
                .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.HOURS.toMillis(6))
                .setRequiresBatteryNotLow(true);
        if (c.checkSelfPermission(android.Manifest.permission.RECEIVE_BOOT_COMPLETED) == PackageManager.PERMISSION_GRANTED) {
            b.setPersisted(true);
        }
        js.schedule(b.build());
    }

    static void cancel(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(JOB_ID);
    }

    /** 「立即備份」按鈕：背景執行，done 在背景執行緒回呼（null＝成功，否則是錯誤訊息） */
    public static void runNowAsync(Context c, final Callback done) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String err = runOnce(app, true);
            if (done != null) done.onDone(err);
        }, "auto-backup").start();
    }

    public interface Callback { void onDone(String errorOrNull); }

    // ---------------- 執行 ----------------

    /** 執行一次備份；回傳 null 表示成功（含內容沒變而沿用），否則是錯誤訊息。force＝內容沒變也另存一份 */
    public static synchronized String runOnce(Context c, boolean force) {
        Context app = c.getApplicationContext();
        File tmp = null;
        try {
            if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("需要 Android 10 以上");
            Source s = loadSource(app);
            tmp = new File(app.getCacheDir(), "auto-backup.tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                s.write(app, out);
            }
            if (tmp.length() == 0) throw new IllegalStateException("備份內容是空的");
            String hash = sha256(tmp);
            SharedPreferences p = prefs(app);
            if (!force && hash.equals(p.getString("lastHash", "")) && p.getString("lastFile", null) != null) {
                p.edit().putLong("lastSuccess", System.currentTimeMillis()).remove("lastError").apply();
                return null;
            }
            String name = fileName(s.appName(), s.extension(), new Date());
            insert(app, s, name, tmp);
            prune(app, s);
            p.edit().putLong("lastSuccess", System.currentTimeMillis()).putString("lastHash", hash)
                    .putString("lastFile", name).remove("lastError").apply();
            return null;
        } catch (Throwable t) {
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            prefs(app).edit().putString("lastError", msg).apply();
            return msg;
        } finally {
            if (tmp != null) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }

    static Source loadSource(Context c) throws Exception {
        ApplicationInfo ai = c.getPackageManager().getApplicationInfo(c.getPackageName(), PackageManager.GET_META_DATA);
        Bundle meta = ai.metaData;
        String cls = meta == null ? null : meta.getString(META_SOURCE);
        if (cls == null) throw new IllegalStateException("AndroidManifest 沒有登記 " + META_SOURCE);
        if (cls.startsWith(".")) cls = c.getPackageName() + cls;
        return (Source) Class.forName(cls).getDeclaredConstructor().newInstance();
    }

    private static Uri collection() {
        return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }

    private static void insert(Context c, Source s, String name, File tmp) throws Exception {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, s.mimeType());
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, folder(s));
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(collection(), v);
        if (uri == null) throw new IllegalStateException("無法在 " + folder(s) + " 建立檔案");
        try (OutputStream out = cr.openOutputStream(uri); InputStream in = new FileInputStream(tmp)) {
            if (out == null) throw new IllegalStateException("無法寫入備份檔");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            cr.delete(uri, null, null);
            throw e;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, done, null, null);
    }

    /** 只刪這個 APP 自己寫的、檔名符合格式的舊備份，保留最近 KEEP 份 */
    private static void prune(Context c, Source s) {
        ContentResolver cr = c.getContentResolver();
        List<String> names = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        try (Cursor cur = cr.query(collection(), new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME},
                sel, new String[]{folder(s)}, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                ids.add(cur.getLong(0));
                names.add(cur.getString(1));
            }
        } catch (Exception ignored) {
            return;
        }
        for (String old : toDelete(names, s.appName(), s.extension(), KEEP)) {
            int i = names.indexOf(old);
            try {
                cr.delete(ContentUris.withAppendedId(collection(), ids.get(i)), null, null);
            } catch (Exception ignored) {
                // 別的安裝寫的檔案刪不掉，留著不影響
            }
        }
    }

    // ---------------- 純邏輯（有 JVM 測試） ----------------

    /** HoonsorAlarm-20261110-0930.zip */
    public static String fileName(String app, String ext, Date when) {
        return app + "-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(when) + "." + ext;
    }

    /** 這個 APP 的備份檔名（其他檔案不碰） */
    public static boolean isBackupName(String name, String app, String ext) {
        return name != null && name.matches(java.util.regex.Pattern.quote(app) + "-\\d{8}-\\d{4}(\\(\\d+\\)| \\(\\d+\\))?\\." + java.util.regex.Pattern.quote(ext));
    }

    /** 要刪掉的舊檔：只看符合格式的檔名，依檔名（就是時間）新到舊排序，超過 keep 份的刪掉 */
    public static List<String> toDelete(List<String> names, String app, String ext, int keep) {
        List<String> mine = new ArrayList<>();
        for (String n : names) if (isBackupName(n, app, ext)) mine.add(n);
        Collections.sort(mine, Collections.reverseOrder());
        return mine.size() <= keep ? new ArrayList<>() : new ArrayList<>(mine.subList(keep, mine.size()));
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
