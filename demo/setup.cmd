@echo off
rem Double-click to run demo\setup.ps1 (KAN-26). Arguments are passed on to setup.ps1.
rem ExecutionPolicy Bypass applies to this one run only and changes no setting.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1" %*
set "CODE=%ERRORLEVEL%"
rem Keep the window open when the file was double-clicked, so the output can be read.
echo %cmdcmdline% | find /i "%~f0" >nul && pause
exit /b %CODE%
