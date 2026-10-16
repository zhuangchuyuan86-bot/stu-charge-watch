@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 汕大充电 Web
where py >nul 2>&1
if errorlevel 1 (
  python server.py --port 8766
) else (
  py -3 server.py --port 8766
)
pause
