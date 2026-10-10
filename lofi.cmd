@echo off
rem runs the lofi server on Windows Command Prompt
rem   lofi
rem   lofi start
rem   lofi stop
rem   lofi autostart on^|off
rem   lofi status
rem   lofi config
rem   lofi set KEY VALUE

setlocal EnableDelayedExpansion

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "CONF=%ROOT%\lofi.conf"
set "LOG_DIR=%ROOT%\logs"
set "JAR=%ROOT%\target\lofi-server.jar"
set "LOG=%LOG_DIR%\server.log"
set "ERR_LOG=%LOG_DIR%\server.err.log"
set "RUN_KEY=HKCU\Software\Microsoft\Windows\CurrentVersion\Run"
set "RUN_NAME=LofiServer"
set "SETTINGS=PORT IDLE_GRACE_SECONDS MEMORY"

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

set "ESC="
set "RED=%ESC%[31m"
set "GREEN=%ESC%[32m"
set "YELLOW=%ESC%[33m"
set "CYAN=%ESC%[36m"
set "GRAY=%ESC%[90m"
set "BOLD=%ESC%[1m"
set "INV=%ESC%[30;46m"
set "RESET=%ESC%[0m"

call :load_config

rem Commands
set "ARG3=%3"
set "ARG4=%4"
if "%~1"=="start"     call :start_server & goto :end
if "%~1"=="stop"      call :stop_server & goto :end
if "%~1"=="status"    call :watch_status & goto :end
if "%~1"=="config"    call :show_config & goto :end
if "%~1"=="set" (
    if not defined ARG3 goto :fail
    if defined ARG4 goto :fail
    call :conf_set "%~2" "%~3"
    goto :end
)
if "%~1"=="autostart" (
    if "%~2"=="on"  call :autostart_on & goto :end
    if "%~2"=="off" call :autostart_off & goto :end
    call :autostart_state
    goto :end
)
goto :menu

:fail
exit /b 1

:end
exit /b %ERRORLEVEL%


rem Settings
:load_config
set "CFG_PORT=7071"
set "CFG_IDLE_GRACE_SECONDS=300"
set "CFG_MEMORY=512"
if exist "%CONF%" (
    for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%CONF%") do (
        for %%k in (%SETTINGS%) do if /i "%%a"=="%%k" if not "%%b"=="" set "CFG_%%k=%%b"
    )
)
set "M_UNIT=%CFG_MEMORY:~-1%"
if /i "%M_UNIT%"=="g" set /a "CFG_MEMORY=%CFG_MEMORY:~0,-1% * 1024"
if /i "%M_UNIT%"=="m" set "CFG_MEMORY=%CFG_MEMORY:~0,-1%"
exit /b 0

:write_config
> "%CONF%" (
    echo # lofi settings
    for %%k in (%SETTINGS%) do echo %%k=!CFG_%%k!
)
call :write_docker_env
exit /b 0

:mem_limit
set /a "MEM_LIMIT=CFG_MEMORY*4"
exit /b 0

:write_docker_env
call :mem_limit
set "ENV_FILE=%ROOT%\.env"
if exist "%ENV_FILE%" (findstr /v /b /c:"LOFI_MEMORY=" /c:"LOFI_MEM_LIMIT=" "%ENV_FILE%" > "%ENV_FILE%.tmp") else (type nul > "%ENV_FILE%.tmp")
>> "%ENV_FILE%.tmp" echo LOFI_MEMORY=%CFG_MEMORY%m
>> "%ENV_FILE%.tmp" echo LOFI_MEM_LIMIT=%MEM_LIMIT%m
move /y "%ENV_FILE%.tmp" "%ENV_FILE%" >nul
exit /b 0

:conf_set
call :conf_check "%~1" "%~2" || exit /b 1
set "CFG_%KEY%=%VAL%"
call :write_config
call :server_pid
if defined PID (
    call :stop_server >nul
    call :start_server Restarting || exit /b 1
)
exit /b 0

:conf_check
set "KEY=%~1"
set "VAL=%~2"
set "KNOWN="
for %%k in (%SETTINGS%) do if /i "%KEY%"=="%%k" set "KEY=%%k" & set "KNOWN=1"
if not defined KNOWN (
    for %%L in (A B C D E F G H I J K L M N O P Q R S T U V W X Y Z) do set "KEY=!KEY:%%L=%%L!"
    echo %RED%Unknown setting '!KEY!'.%RESET%& exit /b 1
)
echo(%VAL%| findstr /r /x "[0-9][0-9]*" >nul || (echo %RED%Bad value '%VAL%' for %KEY%.%RESET%& exit /b 1)
if "%KEY%"=="PORT" (
    if not "%VAL:~5%"=="" (echo %RED%Bad value '%VAL%' for %KEY%.%RESET%& exit /b 1)
    set "NUM=%VAL%"
    call :strip_zeros
    if !NUM! LSS 1 (echo %RED%PORT must be 1-65535.%RESET%& exit /b 1)
    if !NUM! GTR 65535 (echo %RED%PORT must be 1-65535.%RESET%& exit /b 1)
)
exit /b 0

:strip_zeros
if "!NUM:~0,1!"=="0" if not "!NUM!"=="0" (set "NUM=!NUM:~1!"& goto :strip_zeros)
exit /b 0

:show_config
for %%k in (%SETTINGS%) do (
    set "NAME=%%k                      "
    echo !NAME:~0,22! !CFG_%%k!
)
exit /b 0


rem Server
:server_pid
set "PID="
for /f "tokens=2,3,5" %%a in ('netstat -ano -p tcp') do if "%%b"=="0.0.0.0:0" (
    set "ADDR=%%a"
    if "!ADDR:*:=!"=="%CFG_PORT%" set "PID=%%c"
)
exit /b 0

:java_pid
set "PID="
tasklist /nh 2>nul | findstr /i /b "java.exe javaw.exe" >nul || exit /b 0
for /f %%p in ('powershell -NoProfile -NonInteractive -Command "Get-CimInstance Win32_Process -Filter 'Name=''java.exe'' OR Name=''javaw.exe''' | Where-Object { $_.CommandLine -and $_.CommandLine.Contains('%JAR%') } | Select-Object -First 1 -ExpandProperty ProcessId"') do set "PID=%%p"
exit /b 0

:health
call :server_pid
if not defined PID exit /b 1
curl -fs -m 2 "http://127.0.0.1:%CFG_PORT%/api/health" >nul 2>&1
exit /b %ERRORLEVEL%

:build
where mvn >nul 2>&1 || (echo %RED%Maven is not on PATH.%RESET%& exit /b 1)
echo Building the server jar...
pushd "%ROOT%"
call mvn -q -DskipTests package
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" (echo %RED%Build failed.%RESET%& exit /b 1)
echo %GREEN%Build done.%RESET%
exit /b 0

:start_server
set "LABEL=%~1"
if not defined LABEL set "LABEL=Starting"
call :server_pid
if not defined PID call :java_pid
if defined PID (echo Server already running.& exit /b 0)
for %%t in (java ffmpeg curl) do (
    where %%t >nul 2>&1 || (echo %RED%%%t is not on PATH.%RESET%& exit /b 1)
)
if not exist "%JAR%" call :build || exit /b 1
start "" /b javaw -Xmx%CFG_MEMORY%m -jar "%JAR%" --server.port=%CFG_PORT% --lofi.idle-grace-seconds=%CFG_IDLE_GRACE_SECONDS% > "%LOG%" 2> "%ERR_LOG%"
<nul set /p "=%LABEL%"
set /a "I=0"
:start_wait
call :health && (echo(& echo %GREEN%Server up.%RESET%& exit /b 0)
if %I% GTR 2 (
    call :java_pid
    if not defined PID goto :start_down
)
<nul set /p "=."
ping -n 2 127.0.0.1 >nul
set /a "I+=1"
if %I% LSS 30 goto :start_wait
:start_down
echo(
echo %RED%Server down.%RESET%
exit /b 1

:stop_server
call :server_pid
if not defined PID call :java_pid
if not defined PID (echo Server not running.& exit /b 0)
<nul set /p "=Stopping"
taskkill /PID %PID% /T /F >nul 2>&1
set /a "I=0"
:stop_wait
call :server_pid
if not defined PID goto :stop_done
ping -n 2 127.0.0.1 >nul
<nul set /p "=."
set /a "I+=1"
if %I% LSS 10 goto :stop_wait
:stop_done
echo(
echo Server stopped.
exit /b 0

:watch_status
timeout /t 0 /nobreak >nul 2>&1 || (call :status_fetch & call :status_print & exit /b 0)
set "SHOWN="
:watch_loop
call :load_config
call :status_fetch
call :autostart_enabled
set "NOW=!HEALTH!|%AUTO%|%CFG_PORT%|%CFG_IDLE_GRACE_SECONDS%|%CFG_MEMORY%"
if not "!NOW!"=="!SHOWN!" (
    set "SHOWN=!NOW!"
    cls
    call :status_print
)
powershell -NoProfile -Command "$e=[DateTime]::Now.AddSeconds(1); while([DateTime]::Now -lt $e){ if([Console]::KeyAvailable){ [void][Console]::ReadKey($true); exit 1 }; Start-Sleep -Milliseconds 50 }"
if errorlevel 1 exit /b 0
goto :watch_loop

:status_fetch
call :server_pid
set "HEALTH="
if defined PID for /f "delims=" %%h in ('curl -fs -m 2 "http://127.0.0.1:%CFG_PORT%/api/health" 2^>nul') do set "HEALTH=%%h"
exit /b 0

:status_print
if not defined HEALTH (
    echo Server %RED%down%RESET%
    goto :status_autostart
)
echo Server %GREEN%up%RESET%
set "S=!HEALTH:*"stations":{=!"
if "!S:~0,1!"=="}" (set "S=") else (for /f "delims=}" %%a in ("!S!") do set "S=%%a")
if defined S (
    set "S=!S:"=!"
    call :status_stations
)
:status_autostart
call :autostart_state
echo %GRAY%Port %CFG_PORT%   Idle grace second %CFG_IDLE_GRACE_SECONDS%   Memory %CFG_MEMORY%%RESET%
exit /b 0

:status_stations
set "REST="
for /f "tokens=1* delims=," %%a in ("!S!") do (
    for /f "tokens=1,2 delims=:" %%x in ("%%a") do echo   %CYAN%♪%RESET% %%x  %%y listening
    set "REST=%%b"
)
set "S=!REST!"
if defined S goto :status_stations
exit /b 0


rem Autostart
:autostart_enabled
set "AUTO=off"
reg query "%RUN_KEY%" /v %RUN_NAME% >nul 2>&1 && set "AUTO=on"
exit /b 0

:autostart_state
call :autostart_enabled
if "%AUTO%"=="on" (echo Autostart: %GREEN%on%RESET%) else (echo Autostart: %RED%off%RESET%)
exit /b 0

:autostart_on
reg add "%RUN_KEY%" /v %RUN_NAME% /t REG_SZ /d "cmd /c start \"\" /min \"%~f0\" start" /f >nul || (echo %RED%could not write the Run key%RESET%& exit /b 1)
call :autostart_state
exit /b 0

:autostart_off
reg delete "%RUN_KEY%" /v %RUN_NAME% /f >nul 2>&1
call :autostart_state
exit /b 0


rem Menu
:menu
cls
call :load_config
call :server_pid
if defined PID (set "SERVER_ITEM=Stop server") else (set "SERVER_ITEM=Start server")
set "RUNNING=%PID%"
call :autostart_enabled
if "%AUTO%"=="on" (set "AUTO_ITEM=Autostart: %GREEN%on%RESET%") else (set "AUTO_ITEM=Autostart: %RED%off%RESET%")
echo   %CYAN%%BOLD%♪ Lofi Server%RESET%
echo( 
echo   1 %SERVER_ITEM%
echo   2 %AUTO_ITEM%
echo   3 Server status
echo   4 Settings
echo   0 Quit
echo( 
choice /c 12340 /n /m "  Choose 0-4: "
set "PICK=%ERRORLEVEL%"
if "%PICK%"=="5" cls & exit /b 0
if "%PICK%"=="4" goto :settings_menu
if "%PICK%"=="3" call :watch_status & goto :menu
if "%PICK%"=="2" (
    if "%AUTO%"=="on" (call :autostart_off >nul) else (
        call :autostart_on >nul || (echo %RED%could not write the Run key%RESET%& pause >nul)
    )
    goto :menu
)
cls
if defined RUNNING (call :stop_server) else (call :start_server || pause >nul)
goto :menu

:settings_menu
cls
call :load_config
echo   %GRAY%settings%RESET%
echo( 
set /a "N=0"
for %%k in (%SETTINGS%) do (
    set /a "N+=1"
    set "NAME=%%k                      "
    echo    !N!  !NAME:~0,22! !CFG_%%k!
)
echo    0  Back
echo( 
choice /c 1230 /n /m "  Choose 0-3: "
set "PICK=%ERRORLEVEL%"
if "%PICK%"=="4" goto :menu
set /a "N=0"
for %%k in (%SETTINGS%) do (
    set /a "N+=1"
    if "!N!"=="%PICK%" set "KEY=%%k"
)
echo( 
set "VAL="
set /p "VAL=%KEY%: "
if not defined VAL goto :settings_menu
call :conf_check %KEY% "%VAL%" || (pause >nul & goto :settings_menu)
call :conf_set %KEY% "%VAL%" || pause >nul
goto :settings_menu
