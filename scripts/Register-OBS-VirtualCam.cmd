@echo off
REM Re-registers the OBS Virtual Camera DirectShow filter (32 + 64-bit).
REM Use this if the "Start Virtual Camera" button disappears or "Starting the output failed".
title Register OBS Virtual Camera
net session >nul 2>&1
if %errorlevel% neq 0 (
  echo Requesting administrator rights - please approve the UAC prompt...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
set "D=%ProgramFiles%\obs-studio\data\obs-plugins\win-dshow"
echo Registering OBS Virtual Camera filters...
regsvr32 /s "%D%\obs-virtualcam-module32.dll"
regsvr32 /s "%D%\obs-virtualcam-module64.dll"
echo.
echo ============================================================
echo  Done. Fully close OBS (File ^> Exit), reopen it, and the
echo  "Start Virtual Camera" button will be back. Close this window.
echo ============================================================
pause
