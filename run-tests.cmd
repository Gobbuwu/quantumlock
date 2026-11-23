@echo off
rem Runs every backend test on Windows. Double-click this file or run it from a terminal.
rem The full output is written to test-result.txt next to this file.
setlocal
title QuantumLock backend tests
set "ROOT=%~dp0"
cd /d "%ROOT%backend"

rem Pick a JDK that can build Java 21 code. Later lines win, so JDK 21 is preferred over newer ones.
set "JDK="
for /d %%D in ("%ProgramFiles%\Java\jdk-25*") do set "JDK=%%D"
for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-25*") do set "JDK=%%D"
for /d %%D in ("%USERPROFILE%\.jdks\*-21*") do set "JDK=%%D"
for /d %%D in ("%ProgramFiles%\Java\jdk-21*") do set "JDK=%%D"
for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-21*") do set "JDK=%%D"
if defined JDK set "JAVA_HOME=%JDK%"
if defined JAVA_HOME echo Java:  %JAVA_HOME%
if not defined JAVA_HOME echo Java:  no JDK 21 or newer found in the usual folders, using the one on the PATH

rem Pick Maven: the Maven Wrapper when its settings are present, otherwise an installed Maven,
rem otherwise a private copy that is downloaded once into the .tools folder next to this file.
set "MVN_VERSION=3.9.16"
set "QL_TOOLS=%ROOT%.tools"
set "QL_MVN_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MVN_VERSION%/apache-maven-%MVN_VERSION%-bin.zip"
set "MVN=%QL_TOOLS%\apache-maven-%MVN_VERSION%\bin\mvn.cmd"
rem Full path: Windows may be set not to run programs from the current folder (NoDefaultCurrentDirectoryInExePath).
if exist ".mvn\wrapper\maven-wrapper.properties" set "MVN=%ROOT%backend\mvnw.cmd" & goto run
where mvn >nul 2>nul
if not errorlevel 1 set "MVN=mvn" & goto run
if exist "%MVN%" goto run
echo Maven: downloading version %MVN_VERSION% once into %QL_TOOLS%
powershell -NoProfile -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; New-Item -ItemType Directory -Force -Path $env:QL_TOOLS | Out-Null; $zip=Join-Path $env:QL_TOOLS 'maven.zip'; (New-Object Net.WebClient).DownloadFile($env:QL_MVN_URL, $zip); Expand-Archive -Path $zip -DestinationPath $env:QL_TOOLS -Force; Remove-Item $zip"
if exist "%MVN%" goto run
> "%ROOT%test-result.txt" echo Maven could not be downloaded from %QL_MVN_URL%
>> "%ROOT%test-result.txt" echo RUN-TESTS EXIT CODE 1
echo Maven could not be downloaded. Check the internet connection and try again.
goto failed

:run
echo Maven: %MVN%
echo.
echo Running all backend tests. The first run downloads the libraries, which can take a few minutes.
echo Nothing is printed while it runs. Please wait for the RESULT line.
echo.
call "%MVN%" -B verify > "%ROOT%test-result.txt" 2>&1
set "CODE=%ERRORLEVEL%"
>> "%ROOT%test-result.txt" echo.
>> "%ROOT%test-result.txt" echo RUN-TESTS EXIT CODE %CODE%
if "%CODE%"=="0" goto passed

:failed
echo RESULT: FAILED. The details are in test-result.txt
goto done

:passed
echo RESULT: ALL TESTS PASSED

:done
echo.
pause
endlocal
