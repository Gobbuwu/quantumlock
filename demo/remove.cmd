@echo off
rem Double-click to run demo\remove.ps1 (KAN-26). Arguments are passed on to remove.ps1.
rem ExecutionPolicy Bypass applies to this one run only and changes no setting.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0remove.ps1" %*
set "CODE=%ERRORLEVEL%"
rem Keep the window open when the file was double-clicked (no arguments, started by Explorer), so the output can be read.
if not "%~1"=="" goto done
echo %cmdcmdline% | find /i "%~f0" >nul && pause
:done
exit /b %CODE%
