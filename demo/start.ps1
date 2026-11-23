<#
Starts the QuantumLock demo on this computer (KAN-26): the backend and the dashboard's production build, with its
content security policy. Double-click start.cmd, or run:

    demo\start.ps1 [-EnvFile PATH]

Run setup.ps1 once first. PATH is the settings file it wrote (default backend\.env). The backend listens on 127.0.0.1
at PORT from that file (default 8080) and the dashboard on 127.0.0.1:4173. Press Ctrl+C to stop both.
Logs go to demo\.run. macOS and Linux: demo/start.sh does the same.
#>
param(
    [string]$EnvFile = ''
)
. (Join-Path $PSScriptRoot 'common.ps1')

function Test-PortInUse([int]$Port) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $wait = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        return ($wait.AsyncWaitHandle.WaitOne(500) -and $client.Connected)
    }
    catch { return $false }
    finally { $client.Close() }
}

function Test-Url([string]$Url) {
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
        return ($response.StatusCode -eq 200)
    }
    catch { return $false }
}

function Wait-Url([string]$Url, [int]$Seconds, $Process, [string[]]$Logs) {
    for ($i = 0; $i -lt $Seconds; $i++) {
        if (Test-Url $Url) { return }
        if ($Process.HasExited) {
            foreach ($log in $Logs) { Show-LogTail $log }
            Fail 'It stopped while starting.'
        }
        Start-Sleep -Seconds 1
    }
    foreach ($log in $Logs) { Show-LogTail $log }
    Fail "Timed out waiting for $Url"
}

$backend = $null
$dashboard = $null
$code = 0
try {
    if ($EnvFile) { $EnvFile = Get-FullPath $EnvFile } else { $EnvFile = Join-Path $Root 'backend\.env' }
    if (-not (Test-Path -LiteralPath $EnvFile)) { Fail "$EnvFile does not exist. Run demo\setup.ps1 first." }

    # The settings file becomes this script's environment, so the backend gets exactly these settings.
    # Format: NAME=value per line, # comments. Values are taken as they are, without quotes or escapes.
    foreach ($line in [IO.File]::ReadAllLines($EnvFile)) {
        if ($line -match '^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*)$') { Set-TempEnv $Matches[1] $Matches[2] }
    }
    if (-not $env:DB_PASSWORD -or -not $env:MASTER_KEY) { Fail "$EnvFile must set DB_PASSWORD and MASTER_KEY." }
    Mask $env:DB_PASSWORD
    Mask $env:MASTER_KEY
    $backendPort = 8080
    if ($env:PORT) { $backendPort = [int]$env:PORT }
    $dashboardPort = 4173 # fixed in dashboard\vite.config.js
    foreach ($port in @($backendPort, $dashboardPort)) {
        if (Test-PortInUse $port) { Fail "Port $port is already in use. Stop whatever runs there (an earlier demo?) and try again." }
    }

    Step 'Check the tools'
    $java = Find-Java
    $node = Find-Node
    $npm = 'npm'
    if ($OnWindows) { $npm = 'npm.cmd' }

    Step 'Build'
    if (Test-BackendNeedsBuild) { $jar = Build-Backend } else { $jar = Get-BackendJar; Write-Host 'Backend is up to date.' }
    New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
    $dashboardDir = Join-Path $Root 'dashboard'
    $installed = Join-Path $dashboardDir 'node_modules\.package-lock.json'
    $lock = Join-Path $dashboardDir 'package-lock.json'
    Push-Location $dashboardDir
    try {
        if (-not (Test-Path -LiteralPath $installed) -or
            (Get-Item -Force -LiteralPath $lock).LastWriteTimeUtc -gt (Get-Item -Force -LiteralPath $installed).LastWriteTimeUtc) {
            Write-Host "Installing the dashboard's packages."
            Invoke-Native { & $npm ci --no-audit --no-fund 2>&1 | Out-Null }
            if ($LASTEXITCODE -ne 0) { Fail 'npm ci failed.' }
        }
        $buildLog = Join-Path $RunDir 'dashboard-build.log'
        Invoke-Native { & $npm run build 2>&1 | ForEach-Object { "$_" } | Set-Content -LiteralPath $buildLog }
        if ($LASTEXITCODE -ne 0) { Show-LogTail $buildLog; Fail 'The dashboard build failed.' }
    }
    finally { Pop-Location }
    Write-Host 'Dashboard built.'

    Step 'Start'
    $backendLog = Join-Path $RunDir 'backend.log'
    $backendErr = Join-Path $RunDir 'backend-errors.log'
    # Started from demo\.run so the backend reads no .env file of its own, only the settings above.
    $backend = Start-Process -FilePath $java -ArgumentList @('-jar', "`"$jar`"") -WorkingDirectory $RunDir -NoNewWindow -PassThru `
        -RedirectStandardOutput $backendLog -RedirectStandardError $backendErr
    Wait-Url "http://127.0.0.1:$backendPort/api/v1/health" 90 $backend @($backendLog, $backendErr)
    Write-Host 'Backend is up.'
    $dashboardLog = Join-Path $RunDir 'dashboard.log'
    $dashboardErr = Join-Path $RunDir 'dashboard-errors.log'
    Set-TempEnv 'QL_API_TARGET' "http://127.0.0.1:$backendPort"
    # Node runs Vite directly, so $dashboard is the server itself and stopping it stops the dashboard.
    $dashboard = Start-Process -FilePath $node -ArgumentList @('node_modules/vite/bin/vite.js', 'preview') -WorkingDirectory $dashboardDir `
        -NoNewWindow -PassThru -RedirectStandardOutput $dashboardLog -RedirectStandardError $dashboardErr
    Wait-Url "http://127.0.0.1:$dashboardPort/" 60 $dashboard @($dashboardLog, $dashboardErr)

    Write-Host ''
    Write-Host 'QuantumLock is running.'
    Write-Host ''
    Write-Host "    Dashboard:   http://127.0.0.1:$dashboardPort"
    Write-Host "    API docs:    http://127.0.0.1:$backendPort/swagger-ui.html"
    Write-Host ''
    Write-Host 'Open the dashboard and paste the API key from setup. Press Ctrl+C to stop.'
    while (-not $backend.HasExited -and -not $dashboard.HasExited) { Start-Sleep -Seconds 1 }
    if ($backend.HasExited) { Show-LogTail $backendLog; Show-LogTail $backendErr }
    else { Show-LogTail $dashboardLog; Show-LogTail $dashboardErr }
    Fail 'One of the two stopped on its own.'
}
catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    $code = 1
}
finally {
    # Also runs after Ctrl+C.
    foreach ($p in @($dashboard, $backend)) {
        if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    }
    if ($backend -or $dashboard) { Write-Host 'Stopped.' }
    Restore-Env
}
exit $code
