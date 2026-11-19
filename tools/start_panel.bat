@echo off
chcp 65001 >nul
title 学习小卫士 · 手机面板转发
cd /d "%~dp0.."

echo ============================================================
echo  学习小卫士 · 手机面板转发（模拟器调试用）
echo ============================================================
echo.

where adb >nul 2>nul
if errorlevel 1 (
    echo [错误] 找不到 adb，请把 adb 所在目录加入 PATH
    pause
    exit /b 1
)

echo [1/2] 建立端口转发 tcp:8099 ...
adb forward tcp:8099 tcp:8099
echo.

echo [2/2] 启动转发进程（Ctrl+C 结束）
echo       启动后屏幕上会列出手机可访问的地址
echo.
python -u "%~dp0panel_proxy.py" 8100 8099

pause
