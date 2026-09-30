@echo off
cd /d "%~dp0"
where py >nul 2>nul
if %errorlevel% equ 0 (
  start "Bazaar Flip Feedback" http://127.0.0.1:8766
  py "%~dp0feedback-dashboard.py"
  pause
  exit /b
)
where python >nul 2>nul
if %errorlevel% equ 0 (
  start "Bazaar Flip Feedback" http://127.0.0.1:8766
  python "%~dp0feedback-dashboard.py"
  pause
  exit /b
)
echo Python was not found. Install Python 3, then run this file again.
pause
