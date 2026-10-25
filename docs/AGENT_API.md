# Hoonsor Agent API v1 — 倬的鬧鐘

讓語音總控（tw.hoonsor.voicehub）用說的操作鬧鐘與計時器。機器可讀版本：`docs/agent-manifest.json`（與 describe 完全相同，`ManifestTest` 比對；改了 `agent/AgentCatalog.kt` 後設 `UPDATE_MANIFEST=1` 跑一次 `gradlew testDebugUnitTest` 重新產生）。

## 協定

協定、SDK、錯誤碼、risk、撰寫指南與相容規則只寫在語音總控的 [AGENT_PROTOCOL.md](https://github.com/hoonsor/APK-VoiceHub/blob/main/docs/AGENT_PROTOCOL.md)（唯一真實來源）。本檔只列本 APP 的動作。

- 實作：`agent/AgentProvider.kt`（繼承共用 SDK `tw/hoonsor/agent/HoonsorAgent.kt`，整份複製、不修改）、`agent/AgentCatalog.kt`（動作清單）、`agent/AlarmAgent.kt`（各動作）、`agent/AgentLogic.kt`（純邏輯，JVM 測試）。
- 寫入一律走 `data/Repository`（寫完會重新排程）。
- destructive 動作（alarm_delete、timer_delete）第一次回 NEEDS_CONFIRMATION＋確認碼，確認碼綁定「動作＋對象」，兩分鐘內有效、只能用一次。
- 權限：`tw.hoonsor.permission.AGENT`（signature），只有同一把簽章 hoonsor-release.jks 的 APP 拿得到。
- 相容檢查：CI 執行 `python3 tools/agent_compat_check.py --git-baseline docs/agent-manifest.json`。

## 一次性（auto_delete）規則

語音新增的鬧鐘與計時器**預設一次性**：響完（按停止或響太久自動停止）就自動刪除，貪睡時不刪。

| 動作 | 沒給 auto_delete 時 | 說明 |
|---|---|---|
| alarm_create | 單次鬧鐘＝true；重複鬧鐘＝false | 重複鬧鐘一律保留，給 true 也無效 |
| alarm_update | 維持原值 | 重複鬧鐘給 true 會回 INVALID_PARAMS |
| timer_start | true | false＝響完回到原本時長，留在清單裡 |

使用者明確說「固定」「重複」「常駐」「保留」「響完不要刪」時，大腦才給 false。這條規則同時寫在 describe 的 description 與參數說明裡，大腦不需要寫死。

## 動作

| 動作 | risk | 參數 | 範例句 | 回傳 data |
|---|---|---|---|---|
| alarm_list | read | query?, group?, enabled_only?, limit? | 我有哪些鬧鐘 | total、alarms[]（含 auto_delete）、groups[] |
| alarm_create | reversible | hour, minute?, repeat?, days?, date?, memo?, group?, auto_delete? | 明天早上七點叫我 | id、next、auto_delete；undo＝alarm_delete |
| alarm_update | reversible | ids? / query?, hour?, minute?, repeat?, days?, memo?, group?, auto_delete? | 把上班的鬧鐘改成七點十五 | id；undo＝alarm_update 回原值 |
| alarm_toggle | reversible | ids? / query? / all?, enabled | 把七點的鬧鐘關掉 | ids；undo＝反向 |
| alarm_skip_next | reversible | ids? / query? / all?, skip? | 明天的上班鬧鐘略過 | ids；undo＝反向 |
| alarm_delete | destructive | ids? / query? / all? | 刪掉七點的鬧鐘 | ids |
| group_toggle | reversible | name, enabled, kind?（alarm／timer） | 把上班群組關掉 | id；undo＝反向 |
| timer_list | read | query? | 計時器還剩多久 | total、timers[]（含 auto_delete） |
| timer_start | reversible | seconds, label?, group?, auto_delete? | 計時十分鐘 | id、ends_at、auto_delete；undo＝timer_delete |
| timer_control | reversible | ids? / query? / all?, command（pause／resume／reset） | 暫停計時器 | ids；pause↔resume 可 undo |
| timer_delete | destructive | ids? / query? / all? | 刪掉煮麵的計時器 | ids |

## 錯誤

- 找不到對象：NOT_FOUND（會列出現有的名稱）
- 符合多個而動作只能處理一個：AMBIGUOUS（附候選）
- 參數不正確或時間已過：INVALID_PARAMS（message 是可以直接唸出的問句）

## 變更紀錄

- v0.12.0（2026-10-25）：alarm_create、alarm_update、timer_start 新增選填 `auto_delete`；alarm_list、timer_list 回傳 auto_delete。
- v0.12.0 之前（2026-10-20 實作，v0.12.0 首次發布）：11 個動作。
