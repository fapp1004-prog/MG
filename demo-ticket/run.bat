@echo off
setlocal
cd /d "%~dp0"
set PROFILE=%1
set MVN_VER=3.9.9
set TOOLS=%~dp0.maven
set MVN=mvn

where javac >nul 2>nul
if errorlevel 1 goto nojdk

where mvn >nul 2>nul
if not errorlevel 1 goto run

set MVN=%TOOLS%\apache-maven-%MVN_VER%\bin\mvn.cmd
if exist "%MVN%" goto run

echo [demo-ticket] Maven not installed. Downloading Maven %MVN_VER% into .maven folder (one time only)...
if not exist "%TOOLS%" mkdir "%TOOLS%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://archive.apache.org/dist/maven/maven-3/%MVN_VER%/binaries/apache-maven-%MVN_VER%-bin.zip' -OutFile '%TOOLS%\maven.zip'; Expand-Archive -Path '%TOOLS%\maven.zip' -DestinationPath '%TOOLS%' -Force"
if not exist "%MVN%" goto dlfail

:run
if "%PROFILE%"=="fixed" (
  echo [demo-ticket] Starting FIXED version. First run downloads libraries, it can take a few minutes.
) else (
  echo [demo-ticket] Starting VULNERABLE version. First run downloads libraries, it can take a few minutes.
)
echo [demo-ticket] When you see "Started DemoTicketApplication", open http://localhost:8080
start "" cmd /c "timeout /t 40 >nul & start http://localhost:8080"
if "%PROFILE%"=="fixed" (
  call "%MVN%" spring-boot:run -Dspring-boot.run.profiles=fixed
) else (
  call "%MVN%" spring-boot:run
)
pause
exit /b 0

:nojdk
echo.
echo  [demo-ticket] javac not found. Install JDK 17 or newer (e.g. https://adoptium.net) and reopen this window.
echo.
pause
exit /b 1

:dlfail
echo.
echo  [demo-ticket] Maven download failed (network or firewall).
echo   Alternative: open this folder in Eclipse as a Maven project and run DemoTicketApplication.
echo.
pause
exit /b 1
