@echo off
rem Starts the Skill Exchange programs on Windows. JAVA_HOME must point to JDK 17 or newer.
rem
rem   run.bat            the desktop application (starts its own server if none is running)
rem   run.bat server     only the server, with its admin console
rem   run.bat demo       the concurrency demonstration in the terminal
rem   run.bat test       the JUnit tests
rem   run.bat package    builds target\skill-exchange.jar, one file that runs anywhere with Java 17+

cd /d "%~dp0"
set MODE=%1
if "%MODE%"=="" set MODE=app

rem Everything after the first word is passed on to the program.
set ARGS=
shift
:collect
if "%~1"=="" goto run
set ARGS=%ARGS% %1
shift
goto collect

:run
if "%MODE%"=="app"    call mvnw.cmd -q compile javafx:run "-Djavafx.args=%ARGS%" & goto :eof
if "%MODE%"=="server" call mvnw.cmd -q compile exec:java "-Dexec.mainClass=skillexchange.server.ServerMain" "-Dexec.args=%ARGS%" & goto :eof
if "%MODE%"=="demo"   call mvnw.cmd -q compile exec:java "-Dexec.mainClass=skillexchange.Launcher" "-Dexec.args=demo" & goto :eof
if "%MODE%"=="test"   call mvnw.cmd test & goto :eof
if "%MODE%"=="package" call mvnw.cmd -q package -Pall-platforms & goto :eof
echo Usage: run.bat [app^|server^|demo^|test^|package] [options]
