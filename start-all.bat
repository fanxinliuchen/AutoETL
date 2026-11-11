@echo off
REM ============================================================
REM  DHP Web-ETL  -  start all services
REM    MySQL    127.0.0.1:3306   (portable, no Windows service)
REM    etl-server               :8388
REM    etl-ui                   :3800
REM  Idempotent: services already listening are left untouched.
REM ============================================================
setlocal EnableDelayedExpansion
set "BASE=E:\3-code\ERPwebAPI"
set "MYSQL_HOME=E:\3-code\MySQL"
set "MYSQL_BIN=%MYSQL_HOME%\mysql-8.4.11-winx64\bin"

echo ============================================================
echo  DHP Web-ETL startup
echo ============================================================

REM ---------- 1) MySQL ----------
call :portOpen 3306
if not errorlevel 1 goto :mysql_up
echo [....] starting MySQL ...
start "MySQL 3306" /min "%MYSQL_BIN%\mysqld.exe" --defaults-file="%MYSQL_HOME%\my.ini" --console
call :waitPort 3306 45
if errorlevel 1 goto :mysql_fail
echo [ OK ] MySQL listening on 3306
goto :backend
:mysql_up
echo [SKIP] MySQL already listening on 3306
goto :backend
:mysql_fail
echo [FAIL] MySQL did not come up. Check %MYSQL_HOME%\data\mysql-error.log
goto :fail

REM ---------- 2) backend ----------
:backend
call :portOpen 8388
if not errorlevel 1 goto :backend_up
echo [....] starting etl-server on 8388 ^(new window, keep it open^) ...
start "ETL-Server 8388" /D "%BASE%\etl-server" cmd /k "mvn -o -B spring-boot:run -Dspring-boot.run.arguments=--server.port=8388"
call :waitPort 8388 60
if errorlevel 1 goto :backend_fail
echo [ OK ] etl-server listening on 8388
goto :frontend
:backend_up
echo [SKIP] etl-server already listening on 8388
goto :frontend
:backend_fail
echo [FAIL] etl-server did not come up. Check the "ETL-Server 8388" window.
goto :fail

REM ---------- 3) frontend ----------
:frontend
call :portOpen 3800
if not errorlevel 1 goto :frontend_up
echo [....] starting etl-ui on 3800 ^(new window, keep it open^) ...
start "ETL-UI 3800" /D "%BASE%\etl-ui" cmd /k "pnpm dev"
call :waitPort 3800 40
if errorlevel 1 goto :frontend_fail
echo [ OK ] etl-ui listening on 3800
goto :done
:frontend_up
echo [SKIP] etl-ui already listening on 3800
goto :done
:frontend_fail
echo [FAIL] etl-ui did not come up. Check the "ETL-UI 3800" window.
goto :fail

:done
echo ------------------------------------------------------------
echo  Console : http://localhost:3800/
echo  API doc : http://localhost:8388/swagger-ui/index.html
echo ------------------------------------------------------------
endlocal
exit /b 0

:fail
echo ------------------------------------------------------------
echo  Startup aborted. Run status.bat to see what is up.
echo ------------------------------------------------------------
endlocal
exit /b 1

REM ---------- helpers ----------
:portOpen
for /f "tokens=*" %%L in ('netstat -ano ^| findstr /C:":%~1 " ^| findstr /C:"LISTENING"') do exit /b 0
exit /b 1

:waitPort
for /L %%i in (1,1,%~2) do (
    call :portOpen %~1
    if not errorlevel 1 exit /b 0
    ping -n 2 127.0.0.1 >nul
)
exit /b 1
