@echo off
rem Double-click to run demo\start.ps1 (KAN-26). Arguments are passed on to start.ps1.
rem ExecutionPolicy Bypass applies to this one run only and changes no setting.
rem Started from a PowerShell 7 window, this window inherits PowerShell 7's module path, and Windows PowerShell would
rem then fail to load its own modules (Set-Acl not found). Clearing it here, for this window only, lets Windows
rem PowerShell use its default path: https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_psmodulepath
setlocal
set "PSModulePath="
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
set "CODE=%ERRORLEVEL%"
rem Keep the window open when the file was double-clicked (no arguments, started by Explorer), so the output can be read.
if not "%~1"=="" goto done
echo %cmdcmdline% | find /i "%~f0" >nul && pause
:done
exit /b %CODE%
