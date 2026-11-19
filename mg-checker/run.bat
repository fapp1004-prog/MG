@echo off
setlocal
cd /d "%~dp0"
chcp 65001 >nul

where javac >nul 2>nul
if errorlevel 1 goto nojdk

if not exist mg.properties goto firstrun

if exist out rmdir /s /q out
mkdir out
dir /s /b src\*.java > out\sources.txt
javac --release 17 -encoding UTF-8 -d out @out\sources.txt
if errorlevel 1 goto compilefail

start "" cmd /c "timeout /t 2 >nul & start http://127.0.0.1:8090"
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dmg.home="%~dp0." -cp out mg.Main
pause
exit /b 0

:firstrun
copy mg.properties.example mg.properties >nul
echo.
echo  [MG] mg.properties file created.
echo       Put your API key after "api.key=" and save, then run run.bat again.
echo       (No key? set provider=mock to try the screen without a real LLM.)
echo.
start "" notepad mg.properties
pause
exit /b 0

:nojdk
echo.
echo  [MG] javac not found. Install JDK 17 or newer (not JRE).
echo       e.g. https://adoptium.net  -  then reopen this window.
echo.
pause
exit /b 1

:compilefail
echo.
echo  [MG] Compile failed. Check that your JDK version is 17 or newer:  java -version
echo.
pause
exit /b 1
