# runs the lofi server on Windows (PowerShell 5.1 or 7)
#   .\lofi.ps1
#   .\lofi.ps1 start
#   .\lofi.ps1 stop
#   .\lofi.ps1 autostart on|off
#   .\lofi.ps1 status
#   .\lofi.ps1 config
#   .\lofi.ps1 set KEY VALUE

$ArgCount = $args.Count
$Command = if ($ArgCount -gt 0) { [string]$args[0] } else { '' }
$Key     = if ($ArgCount -gt 1) { [string]$args[1] } else { '' }
$Value   = if ($ArgCount -gt 2) { [string]$args[2] } else { '' }

$ErrorActionPreference = 'Stop'

$Root    = $PSScriptRoot
$Conf    = Join-Path $Root 'lofi.conf'
$LogDir  = Join-Path $Root 'logs'
$Jar     = Join-Path $Root 'target\lofi-server.jar'
$Log     = Join-Path $LogDir 'server.log'
$ErrLog  = Join-Path $LogDir 'server.err.log'
$RunKey  = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$RunName = 'LofiServer'

New-Item -ItemType Directory -Force $LogDir | Out-Null

# Settings
$Settings = [ordered]@{
    PORT               = @{ Default = '7071'; Prop = 'server.port';             Valid = '^\d{1,5}$' }
    IDLE_GRACE_SECONDS = @{ Default = '300';  Prop = 'lofi.idle-grace-seconds'; Valid = '^\d+$' }
    MEMORY             = @{ Default = '512';  Prop = $null;                     Valid = '^\d+$' }
}

function Read-Config {
    $cfg = [ordered]@{}
    foreach ($k in $Settings.Keys) { $cfg[$k] = $Settings[$k].Default }
    if (Test-Path $Conf) {
        foreach ($line in Get-Content $Conf) {
            if ($line -match '^\s*([A-Z_]+)\s*=\s*(.*?)\s*$' -and $cfg.Contains($Matches[1]) -and $Matches[2]) {
                $cfg[$Matches[1]] = $Matches[2]
            }
        }
    }
    if ($cfg.MEMORY -match '^(\d+)[gG]$') { $cfg.MEMORY = [string]([int]$Matches[1] * 1024) }
    elseif ($cfg.MEMORY -match '^(\d+)[mM]$') { $cfg.MEMORY = $Matches[1] }
    return $cfg
}

function Write-Config($cfg) {
    $lines = @('# lofi settings')
    foreach ($k in $cfg.Keys) { $lines += "$k=$($cfg[$k])" }
    [IO.File]::WriteAllLines($Conf, [string[]]$lines, (New-Object Text.ASCIIEncoding))
    Write-DockerEnv $cfg
}

function Get-MemLimit($cfg) { return [int]$cfg.MEMORY * 4 }

function Write-DockerEnv($cfg) {
    $envFile = Join-Path $Root '.env'
    $keep = @()
    if (Test-Path $envFile) { $keep = @(Get-Content $envFile | Where-Object { $_ -notmatch '^LOFI_(MEMORY|MEM_LIMIT)=' }) }
    $lines = $keep + "LOFI_MEMORY=$($cfg.MEMORY)m" + "LOFI_MEM_LIMIT=$(Get-MemLimit $cfg)m"
    [IO.File]::WriteAllLines($envFile, [string[]]$lines, (New-Object Text.ASCIIEncoding))
}

function Test-Conf([string] $k, [string] $v) {
    $k = $k.ToUpper()
    if (-not $Settings.Contains($k)) { throw "Unknown setting '$k'." }
    if ($v -notmatch $Settings[$k].Valid) { throw "Bad value '$v' for $k." }
    if ($k -eq 'PORT' -and ([int]$v -lt 1 -or [int]$v -gt 65535)) { throw 'PORT must be 1-65535.' }
}

function Set-Conf([string] $k, [string] $v) {
    Test-Conf $k $v
    $k = $k.ToUpper()
    $cfg = Read-Config
    $cfg[$k] = $v
    Write-Config $cfg
    $script:StartOk = $true
    if (Get-ServerPid) {
        Stop-Server *> $null
        Start-Server 'Restarting'
    }
}

function Show-Config {
    $cfg = Read-Config
    foreach ($k in $cfg.Keys) { Write-Host ('{0,-22} {1,-8}' -f $k, $cfg[$k]) }
}

# Server
function Get-ServerPid {
    $port = (Read-Config).PORT
    foreach ($line in (& netstat -ano -p tcp)) {
        $f = -split $line
        if ($f.Count -ge 5 -and $f[2] -eq '0.0.0.0:0' -and $f[1] -match ":$port$") { return [int]$f[4] }
    }
    return $null
}

function Get-JavaPid {
    $java = @(Get-Process java, javaw -ErrorAction SilentlyContinue)
    if ($java.Count -eq 0) { return $null }
    $filter = ($java | ForEach-Object { "ProcessId=$($_.Id)" }) -join ' OR '
    $p = Get-CimInstance Win32_Process -Filter $filter |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($Jar) } | Select-Object -First 1
    if ($p) { return [int]$p.ProcessId }
    return $null
}

function Get-Health {
    if (-not (Get-ServerPid)) { return $null }
    try { return Invoke-RestMethod -Uri "http://127.0.0.1:$((Read-Config).PORT)/api/health" -TimeoutSec 2 } catch { return $null }
}

function Invoke-Build {
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Maven is not on PATH.' }
    Write-Host 'Building the server jar...'
    Push-Location $Root
    try {
        & mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
    } finally { Pop-Location }
    Write-Host 'Build done.' -ForegroundColor Green
}

function Start-Server([string] $Label = 'Starting') {
    $script:StartOk = $false
    if ((Get-ServerPid) -or (Get-JavaPid)) { Write-Host 'Server already running.'; $script:StartOk = $true; return }
    foreach ($tool in 'java', 'ffmpeg', 'curl') {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool is not on PATH." }
    }
    if (-not (Test-Path $Jar)) { Invoke-Build }

    $cfg = Read-Config
    $javaArgs = @("-Xmx$($cfg.MEMORY)m", '-jar', "`"$Jar`"")
    foreach ($k in $Settings.Keys) {
        if ($Settings[$k].Prop) { $javaArgs += "--$($Settings[$k].Prop)=$($cfg[$k])" }
    }

    Start-Process java -ArgumentList $javaArgs -WorkingDirectory $Root -WindowStyle Hidden `
        -RedirectStandardOutput $Log -RedirectStandardError $ErrLog

    Write-Host -NoNewline $Label
    for ($i = 0; $i -lt 30; $i++) {
        if (Get-Health) { Write-Host ''; Write-Host 'Server up.' -ForegroundColor Green; $script:StartOk = $true; return }
        if ($i -gt 2 -and -not (Get-JavaPid)) { break }
        Start-Sleep -Seconds 1
        Write-Host -NoNewline '.'
    }
    Write-Host ''
    Write-Host 'Server down.' -ForegroundColor Red
}

function Stop-Server {
    $id = Get-ServerPid
    if (-not $id) { $id = Get-JavaPid }
    if (-not $id) { Write-Host 'Server not running.'; return }
    Write-Host -NoNewline 'Stopping'
    & taskkill /PID $id /T /F 2>&1 | Out-Null
    for ($i = 0; $i -lt 10 -and (Get-Process -Id $id -ErrorAction SilentlyContinue); $i++) {
        Start-Sleep -Seconds 1
        Write-Host -NoNewline '.'
    }
    Write-Host ''
    Write-Host 'Server stopped.'
}

function Show-Status($h = (Get-Health)) {
    if ($h) {
        Write-Host 'Server ' -NoNewline; Write-Host 'up' -ForegroundColor Green
        foreach ($st in $h.stations.PSObject.Properties) {
            Write-Host '  ' -NoNewline; Write-Host '♪' -ForegroundColor Cyan -NoNewline
            Write-Host " $($st.Name)  $($st.Value) listening"
        }
    } else {
        Write-Host 'Server ' -NoNewline; Write-Host 'down' -ForegroundColor Red
    }
    Write-AutostartState
    $cfg = Read-Config
    Write-Host "Port $($cfg.PORT)   Idle grace second $($cfg.IDLE_GRACE_SECONDS)   Memory $($cfg.MEMORY)" -ForegroundColor DarkGray
}

function Watch-Status {
    if ([Console]::IsInputRedirected) { Show-Status; return }
    $shown = $null
    while ($true) {
        $h = Get-Health
        $cfg = Read-Config
        $now = "$($h | ConvertTo-Json -Compress -Depth 4)|$(Test-Autostart)|$($cfg.Values -join ',')"
        if ($now -ne $shown) { $shown = $now; Clear-Host; Show-Status $h }
        for ($t = 0; $t -lt 10; $t++) {
            if ([Console]::KeyAvailable) { [void][Console]::ReadKey($true); return }
            Start-Sleep -Milliseconds 100
        }
    }
}

# Autostart
function Test-Autostart {
    return $null -ne (Get-ItemProperty $RunKey -Name $RunName -ErrorAction SilentlyContinue)
}

function Write-AutostartState {
    Write-Host 'Autostart: ' -NoNewline
    if (Test-Autostart) { Write-Host 'on' -ForegroundColor Green } else { Write-Host 'off' -ForegroundColor Red }
}

function Set-AutostartOn {
    $cmd = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$PSCommandPath`" start"
    try { Set-ItemProperty $RunKey -Name $RunName -Value $cmd } catch { throw 'could not write the Run key' }
    Write-AutostartState
}

function Set-AutostartOff {
    Remove-ItemProperty $RunKey -Name $RunName -ErrorAction SilentlyContinue
    Write-AutostartState
}

# Menu
function Read-Key {
    $k = [Console]::ReadKey($true)
    switch ($k.Key) {
        'UpArrow'   { return 'up' }
        'DownArrow' { return 'down' }
        'Enter'     { return 'enter' }
        'Escape'    { return 'esc' }
        'Q'         { return 'esc' }
        'K'         { return 'up' }
        'J'         { return 'down' }
    }
    if ($k.KeyChar -match '^[0-9]$') { return [string]$k.KeyChar }
    return ''
}

function Show-Choice([string] $title, [int] $sel, [string[]] $items) {
    Clear-Host
    if ($title) { Write-Host "  $title" -ForegroundColor DarkGray }
    else { Write-Host "  $([char]27)[1m♪ Lofi Server$([char]27)[22m" -ForegroundColor Cyan }
    [Console]::WriteLine()
    for ($i = 0; $i -lt $items.Count; $i++) {
        $item = $items[$i]
        if ($i -eq $sel) {
            if ($item -match '^(Autostart: )(on|off)$') {
                Write-Host '  ' -NoNewline
                Write-Host " ▸ $($Matches[1])" -ForegroundColor Black -BackgroundColor Cyan -NoNewline
                Write-Host $Matches[2] -ForegroundColor $(if ($Matches[2] -eq 'on') { 'Green' } else { 'Red' }) -BackgroundColor Cyan -NoNewline
                Write-Host ' ' -BackgroundColor Cyan
            } else {
                Write-Host '  ' -NoNewline
                Write-Host " ▸ $item " -ForegroundColor Black -BackgroundColor Cyan
            }
            continue
        }
        if ($item -match '^(Autostart: )(on|off)$') {
            Write-Host "    $($Matches[1])" -NoNewline
            Write-Host $Matches[2] -ForegroundColor $(if ($Matches[2] -eq 'on') { 'Green' } else { 'Red' })
        } else {
            Write-Host "    $item"
        }
    }
}

function Wait-Key { [void][Console]::ReadKey($true) }

function Show-SettingsMenu {
    $sel = 0
    while ($true) {
        $cfg = Read-Config
        $keys = @($Settings.Keys)
        $n = $keys.Count
        $items = @($keys | ForEach-Object { '{0,-22} {1,-6}' -f $_, $cfg[$_] }) + 'Back'
        Show-Choice 'settings' $sel $items
        switch (Read-Key) {
            'up'   { $sel = ($sel + $n) % ($n + 1) }
            'down' { $sel = ($sel + 1) % ($n + 1) }
            'esc'  { return }
            'enter' {
                if ($sel -eq $n) { return }
                $k = $keys[$sel]
                [Console]::WriteLine()
                $v = Read-Host $k
                if ($v) {
                    try {
                        Test-Conf $k $v
                        Set-Conf $k $v
                        if (-not $script:StartOk) { Wait-Key }
                    } catch { Write-Host $_.Exception.Message -ForegroundColor Red; Wait-Key }
                }
            }
        }
    }
}

function Show-Menu {
    $sel = 0
    $count = 5
    while ($true) {
        $running = [bool](Get-ServerPid)
        $server = if ($running) { 'Stop server' } else { 'Start server' }
        $auto = if (Test-Autostart) { 'Autostart: on' } else { 'Autostart: off' }
        Show-Choice '' $sel @($server, $auto, 'Server status', 'Settings', 'Quit')
        $key = Read-Key
        if ($key -eq 'up') { $sel = ($sel + $count - 1) % $count; continue }
        if ($key -eq 'down') { $sel = ($sel + 1) % $count; continue }
        if ($key -eq 'esc') { Clear-Host; return }
        if ($key -match '^[1-5]$') { $sel = [int]$key - 1 }
        elseif ($key -ne 'enter') { continue }
        try {
            switch ($sel) {
                0 {
                    Clear-Host
                    if ($running) { Stop-Server } else { Start-Server; if (-not $script:StartOk) { Wait-Key } }
                }
                1 { if (Test-Autostart) { Set-AutostartOff *> $null } else { Set-AutostartOn *> $null } }
                2 { Watch-Status }
                3 { Show-SettingsMenu }
                4 { Clear-Host; return }
            }
        } catch {
            Write-Host $_.Exception.Message -ForegroundColor Red
            Wait-Key
        }
    }
}

# Commands
try {
    switch -CaseSensitive ($Command) {
        'start'  { Start-Server; if (-not $script:StartOk) { exit 1 } }
        'stop'   { Stop-Server }
        'autostart' {
            if ($Key -ceq 'on') { Set-AutostartOn }
            elseif ($Key -ceq 'off') { Set-AutostartOff }
            else { Write-AutostartState }
        }
        'status' { Watch-Status }
        'config' { Show-Config }
        'set'    {
            if ($ArgCount -ne 3) { exit 1 }
            Set-Conf $Key $Value
            if (-not $script:StartOk) { exit 1 }
        }
        default  { Show-Menu }
    }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
