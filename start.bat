@echo off
REM ============================================================
REM  Beiming Drive - local launcher
REM  PostgreSQL(5440 container) + backend/SPA(8140)
REM  ASCII-only on purpose: cmd.exe parses .bat with the OEM
REM  codepage (GBK on zh-CN), so UTF-8 Chinese comments corrupt
REM  the REM lines and kill the launcher before it starts.
REM ============================================================
setlocal
cd /d "%~dp0"

set JAVA=C:\Users\15780\dev\jdk-21\bin\java.exe
set JAR=%~dp0backend\target\beiming-drive-0.1.0-SNAPSHOT.jar

if not exist "%JAR%" (
  echo [!] jar not found. Build it first:
  echo     C:\Users\15780\dev\apache-maven-3.9.16\bin\mvn.cmd -B -DskipTests -f backend\pom.xml package
  pause
  exit /b 1
)

echo [1/2] starting PostgreSQL on 5440 ...
docker compose -f "%~dp0compose.yml" up -d
if errorlevel 1 (
  echo [!] docker compose failed - is Docker Desktop running?
  pause
  exit /b 1
)

echo [2/2] waiting for postgres to be healthy ...
:waitpg
docker exec drive-postgres pg_isready -U beiming -d beiming_drive >nul 2>&1
if errorlevel 1 (
  timeout /t 2 /nobreak >nul
  goto waitpg
)

echo.
echo ============================================================
echo   Beiming Drive   http://127.0.0.1:8140
echo   login           first registered account becomes ADMIN
echo ============================================================
echo.

start "BeimingDrive-8140" cmd /c ""%JAVA%" -jar "%JAR%""
timeout /t 10 /nobreak >nul
start "" http://127.0.0.1:8140