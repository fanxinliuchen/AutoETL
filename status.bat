@echo off
REM ============================================================
REM  DHP Web-ETL  -  service status
REM ============================================================
setlocal EnableDelayedExpansion

echo ============================================================
echo  DHP Web-ETL status
echo ============================================================

call :report 3306 "MySQL      "
call :report 8388 "etl-server "
call :report 3800 "etl-ui     "

echo ------------------------------------------------------------
where curl >nul 2>&1
if errorlevel 1 (
    echo  curl not found, skip HTTP check
    goto :caps
)
echo  backend  /etl/pipeline/page : 
curl -s -o nul -w "     HTTP %%{http_code}  (%%{time_total}s)\n" -X POST http://localhost:8388/etl/pipeline/page -H "Content-Type: application/json" -d "{\"pageNum\":1,\"pageSize\":1}"
echo  frontend /                  : 
curl -s -o nul -w "     HTTP %%{http_code}  (%%{time_total}s)\n" http://localhost:3800/

:caps
echo ------------------------------------------------------------
echo  Console : http://localhost:3800/
echo  API doc : http://localhost:8388/swagger-ui/index.html
echo ------------------------------------------------------------
endlocal
exit /b 0

REM %1=port %2=label
:report
set "PIDS="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /C:":%~1 " ^| findstr /C:"LISTENING"') do (
    if not defined PIDS set "PIDS=%%p"
)
if defined PIDS (
    echo  [ UP ] %~2 port %~1   pid !PIDS!
) else (
    echo  [DOWN] %~2 port %~1
)
exit /b 0
