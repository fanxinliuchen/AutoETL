@echo off
REM ============================================================
REM  DHP Web-ETL  -  stop all services
REM    usage:  stop-all.bat [mysqlRootPassword]
REM    password order: arg1  >  env MYSQL_ROOT_PWD  >  Root123456
REM    MySQL is shut down gracefully first (mysqladmin shutdown).
REM ============================================================
setlocal EnableDelayedExpansion
set "MYSQL_HOME=E:\3-code\MySQL"
set "MYSQL_BIN=%MYSQL_HOME%\mysql-8.4.11-winx64\bin"

set "MYSQL_PWD=%~1"
if "%MYSQL_PWD%"=="" set "MYSQL_PWD=%MYSQL_ROOT_PWD%"
if "%MYSQL_PWD%"=="" set "MYSQL_PWD=Root123456"

echo ============================================================
echo  DHP Web-ETL shutdown
echo ============================================================

REM ---------- 1) frontend ----------
call :killPort 3800 etl-ui
REM ---------- 2) backend ----------
call :killPort 8388 etl-server

REM ---------- 3) MySQL (graceful) ----------
call :portOpen 3306
if errorlevel 1 (
    echo [SKIP] MySQL not running
) else (
    echo [....] shutting down MySQL ...
    "%MYSQL_BIN%\mysqladmin.exe" -h 127.0.0.1 -P 3306 -u root -p%MYSQL_PWD% --connect-timeout=5 shutdown >nul 2>&1
    if errorlevel 1 (
        echo [WARN] graceful shutdown failed, forcing kill
        taskkill /F /IM mysqld.exe >nul 2>&1
    )
    call :waitClosed 3306 20
    if errorlevel 1 (
        echo [WARN] MySQL still listening on 3306
    ) else (
        echo [ OK ] MySQL stopped
    )
)

echo ------------------------------------------------------------
echo  Done.
echo ------------------------------------------------------------
endlocal
exit /b 0

REM ---------- helpers ----------
:killPort
set "FOUND="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /C:":%~1 " ^| findstr /C:"LISTENING"') do (
    taskkill /PID %%p /T /F >nul 2>&1
    set "FOUND=1"
)
if defined FOUND (
    echo [ OK ] stopped %~2 ^(port %~1^)
) else (
    echo [SKIP] %~2 not running
)
exit /b 0

:portOpen
for /f "tokens=*" %%L in ('netstat -ano ^| findstr /C:":%~1 " ^| findstr /C:"LISTENING"') do exit /b 0
exit /b 1

:waitClosed
for /L %%i in (1,1,%~2) do (
    call :portOpen %~1
    if errorlevel 1 exit /b 0
    ping -n 2 127.0.0.1 >nul
)
exit /b 1
