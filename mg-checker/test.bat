@echo off
setlocal
cd /d "%~dp0"
chcp 65001 >nul
if exist out-test rmdir /s /q out-test
mkdir out-test
dir /s /b src\*.java test\*.java > out-test\sources.txt
javac --release 17 -encoding UTF-8 -d out-test @out-test\sources.txt
if errorlevel 1 goto fail
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dmg.home="%~dp0." -cp out-test mg.TestMain
pause
exit /b 0
:fail
echo [MG] test compile failed
pause
exit /b 1
