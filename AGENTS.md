# AGENTS.md — 鬧鐘 HoonsorAlarm（APK-Alarm）

所有 AI 共用的專案說明書。進度與交接看 `PROJECT_STATUS.md`。
> 本檔由 38 號額度接力排程在 2026-11-04 依程式與文件補建，標「建議（未確認）」的內容要倬確認。

## 這是什麼
倬自用的 Android 鬧鐘，以「絕對不能漏響」為第一優先，整合鬧鐘、計時器、碼錶、語音建立、MiniMax 語音與語音總控（Hoonsor Agent API）。使用說明在 `README.md`，版本與待辦在 `PROJECT_STATUS.md`。中文資料夾 `D:\01-Project\32-鬧鐘APP` 只放舊快照。

## 技術棧
Kotlin、Jetpack Compose（BOM）、Room（schema 在 `app/schemas`）；compileSdk／targetSdk 35、minSdk 26；套件名 `com.hoonsor.alarm`；依賴集中在 `gradle/libs.versions.toml`。JDK 用 `~/.jdks/jbr-21.0.11`（Android Studio 內建 JBR 25 跑不動 Gradle 8.11）。

## 目錄
| 路徑 | 內容 |
|---|---|
| `app/src/main/java/com/hoonsor/alarm/` | 主程式：agent、backup、data、domain、ringing、scheduler、sound、tts、ui、update、voice |
| `app/src/main/java/tw/hoonsor/agent/` | Hoonsor Agent API 共用 SDK |
| `docs/AGENT_API.md` | 語音總控可呼叫的動作清單（協定唯一來源在 APK-VoiceHub 的 `docs/AGENT_PROTOCOL.md`） |
| `.github/workflows/build.yml` | CI：push main 更新 latest 預先發行版；push `vX.Y.Z` tag 發正式 Release |
| `scripts/sync-from-github.bat` | 從 GitHub 同步 |

## 指令
```powershell
$env:JAVA_HOME="$HOME\.jdks\jbr-21.0.11"
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
# 發版：改 app/build.gradle.kts 的 versionCode/versionName → PROJECT_STATUS 加版本段落 → push main 與同名 tag
```

## 發版與簽章
- 共用簽章 `hoonsor-release.jks`，CI 從 Secrets 讀 `SIGNING_*`；憑證 SHA-256 開頭 `514ec00e`（和語音總控相同，Agent API 的 signature 權限才拿得到）。不要把金鑰放進 repo。
- 推 tag 前先確認 tag 與 `versionName` 一致，CI 會檢查。

## 規則（Always／Ask／Never）
- **Always**：改程式前先 commit 快照或開 feature branch；收工更新 `PROJECT_STATUS.md` 的「下一步」與交接紀錄；文件、註解、commit 用繁體中文（Conventional Commits）。
- **Ask**：合併 main、deploy、對外發布、寄信發訊息、改系統設定、刪除資料（刪檔一律移到 `_trash/`）。
- **Never**：force push 或改寫歷史；把 `.env` 明文、金鑰、`.env.keys` 放進 git；讀取或顯示解密後的 `.env` 與 `DOTENV_PRIVATE_KEY`；把人事或公文資料送到雲端 AI（需先在本機去識別化）。
