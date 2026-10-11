@echo off
chcp 65001 >nul
cd /d "%~dp0.."
echo 從 GitHub 同步最新版本（Claude 已經把檔案寫進這個資料夾，這一步只更新 Git 紀錄，不會刪除你的修改）
git fetch origin || goto :fail
git reset --mixed origin/main || goto :fail
echo.
echo 完成，目前版本：
git log -1 --oneline
echo 尚未提交的變更（空白代表和 GitHub 完全一致）：
git status -s
pause
exit /b 0
:fail
echo 同步失敗，請把上面的訊息截圖給 Claude。
pause
exit /b 1
