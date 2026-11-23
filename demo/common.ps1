# Shared by demo\setup.ps1, demo\start.ps1 and demo\remove.ps1 (KAN-26). Not meant to be run on its own.
# Works in Windows PowerShell 5.1 and in PowerShell 7.

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RunDir = Join-Path $Root (Join-Path 'demo' '.run')
$OnWindows = ($PSVersionTable.PSEdition -eq 'Desktop') -or $IsWindows
$BuildStamp = Join-Path $RunDir 'backend-built'

function Step([string]$Text) { Write-Host ''; Write-Host "== $Text" }
function Fail([string]$Text) { throw [System.InvalidOperationException]::new($Text) }
function Mask([string]$Value) { if ($env:GITHUB_ACTIONS -eq 'true') { Write-Host "::add-mask::$Value" } }

# Environment variables set by these scripts are put back as they were at the end, so nothing secret stays in the
# PowerShell window the script was started from.
$script:SavedEnv = @{}
function Set-TempEnv([string]$Name, [string]$Value) {
    if (-not $script:SavedEnv.ContainsKey($Name)) {
        $script:SavedEnv[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
function Restore-Env {
    foreach ($name in @($script:SavedEnv.Keys)) {
        [Environment]::SetEnvironmentVariable($name, $script:SavedEnv[$name], 'Process')
    }
    $script:SavedEnv = @{}
}

# Runs a native program without turning its error output into PowerShell errors (Windows PowerShell 5.1 does that
# when the output is redirected). The caller checks $LASTEXITCODE.
function Invoke-Native([scriptblock]$Block) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Block } finally { $ErrorActionPreference = $old }
}

function Get-FullPath([string]$Path) {
    return [IO.Path]::GetFullPath([IO.Path]::Combine((Get-Location).Path, $Path))
}

function Test-DbName([string]$Name) {
    # Database and user share one name, used unquoted in SQL, so only plain lower-case names are accepted.
    if ($Name -cnotmatch '^[a-z_][a-z0-9_]{0,62}$') {
        Fail "Database name '$Name' is not allowed. Use lower-case letters, digits and _ (at most 63)."
    }
}

function Get-JavaMajor([string]$Java) {
    $text = Invoke-Native { & $Java -version 2>&1 | ForEach-Object { "$_" } }
    $match = [regex]::Match(($text -join "`n"), 'version "(\d+)')
    if ($match.Success) { return [int]$match.Groups[1].Value }
    return 0
}

# Finds Java 21 or later: JAVA_HOME, then the usual Windows install folders (JDK 21 first), then the PATH.
# Sets JAVA_HOME for the Maven Wrapper and returns the java program to run.
function Find-Java {
    $homes = @()
    if ($env:JAVA_HOME) { $homes += $env:JAVA_HOME }
    if ($OnWindows) {
        foreach ($pattern in @('Eclipse Adoptium\jdk-21*', 'Java\jdk-21*', 'Microsoft\jdk-21*', 'Eclipse Adoptium\jdk-2*', 'Java\jdk-2*')) {
            $homes += @(Get-ChildItem -Path (Join-Path $env:ProgramFiles $pattern) -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending | ForEach-Object { $_.FullName })
        }
    }
    $exe = 'java'
    if ($OnWindows) { $exe = 'java.exe' }
    foreach ($h in $homes) {
        $java = Join-Path (Join-Path $h 'bin') $exe
        if ((Test-Path -LiteralPath $java) -and ((Get-JavaMajor $java) -ge 21)) {
            Set-TempEnv 'JAVA_HOME' $h
            return $java
        }
    }
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath -and ((Get-JavaMajor $onPath.Source) -ge 21)) {
        if ($env:JAVA_HOME) { Set-TempEnv 'JAVA_HOME' '' } # a JAVA_HOME with an older Java would mislead the Maven Wrapper
        return $onPath.Source
    }
    Fail 'Java 21 or later was not found. Install JDK 21 (https://adoptium.net) and try again.'
}

function Find-Node {
    $node = Get-Command node -ErrorAction SilentlyContinue
    if (-not $node) { Fail 'Node.js was not found. Install Node.js 22 (https://nodejs.org) and try again.' }
    $version = (Invoke-Native { & $node.Source -p 'process.versions.node' }) -join ''
    $parts = $version.Split('.')
    $major = [int]$parts[0]
    $minor = [int]$parts[1]
    # Vite 8 needs Node 20.19 or a later 20.x, or 22.12 or later (not 21).
    if ($major -lt 20 -or ($major -eq 20 -and $minor -lt 19) -or $major -eq 21 -or ($major -eq 22 -and $minor -lt 12)) {
        Fail "Node.js 20.19, 22.12 or later is needed, found $version."
    }
    return $node.Source
}

function Find-Psql {
    $psql = Get-Command psql -ErrorAction SilentlyContinue
    if ($psql) { $path = $psql.Source }
    elseif ($OnWindows) {
        $found = Get-ChildItem -Path (Join-Path $env:ProgramFiles 'PostgreSQL\*\bin\psql.exe') -ErrorAction SilentlyContinue |
            Sort-Object { [int]$_.Directory.Parent.Name } -Descending | Select-Object -First 1
        if ($found) { $path = $found.FullName }
    }
    if (-not $path) { Fail 'psql was not found. Install PostgreSQL (https://www.postgresql.org/download/) and try again.' }
    $version = (Invoke-Native { & $path --version }) -join ''
    $match = [regex]::Match($version, '(\d+)')
    if (-not $match.Success -or [int]$match.Groups[1].Value -lt 15) { Fail "psql 15 or later is needed, found $version." }
    return $path
}

function Get-BackendJar {
    $jar = Get-ChildItem -Path (Join-Path $Root 'backend\target') -Filter 'quantumlock-backend-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
    if ($jar) { return $jar.FullName }
    return $null
}

# demo\.run\backend-built marks the last successful build. Maven leaves an up-to-date jar untouched, so the jar's own
# date cannot tell whether the sources changed since.
function Test-BackendNeedsBuild {
    if (-not (Get-BackendJar) -or -not (Test-Path -LiteralPath $BuildStamp)) { return $true }
    $built = (Get-Item -LiteralPath $BuildStamp).LastWriteTimeUtc
    $newer = Get-ChildItem -Path (Join-Path $Root 'backend\src') -Recurse -File |
        Where-Object { $_.LastWriteTimeUtc -gt $built } | Select-Object -First 1
    return [bool]$newer -or ((Get-Item -LiteralPath (Join-Path $Root 'backend\pom.xml')).LastWriteTimeUtc -gt $built)
}

function Build-Backend {
    Write-Host 'Building the backend (the first build downloads the libraries and can take a few minutes).'
    New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
    $started = [DateTime]::UtcNow
    $mvnw = './mvnw'
    if ($OnWindows) { $mvnw = '.\mvnw.cmd' }
    Push-Location (Join-Path $Root 'backend')
    try { Invoke-Native { & $mvnw -B -q package -DskipTests } } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { Fail 'The backend build failed.' }
    $jar = Get-BackendJar
    if (-not $jar) { Fail 'The backend build did not produce a jar.' }
    Set-Content -LiteralPath $BuildStamp -Value 'built' -Encoding ASCII
    (Get-Item -LiteralPath $BuildStamp).LastWriteTimeUtc = $started
    return $jar
}

# Connects to PostgreSQL as an administrator: PGHOST, PGPORT and PGUSER as usual (defaults localhost, 5432, postgres).
# The password comes from PGPASSWORD or is asked for once. It stays in this script's environment only.
function Connect-Admin([string]$Psql) {
    if (-not $env:PGHOST) { Set-TempEnv 'PGHOST' 'localhost' }
    if (-not $env:PGPORT) { Set-TempEnv 'PGPORT' '5432' }
    if (-not $env:PGUSER) { Set-TempEnv 'PGUSER' 'postgres' }
    if ($env:PGDATABASE) { Set-TempEnv 'PGDATABASE' '' }
    Invoke-Native { & $Psql -X -w -tA -d postgres -c 'SELECT 1' 2>&1 | Out-Null }
    if ($LASTEXITCODE -ne 0) {
        if (-not $env:PGPASSWORD -and -not [Console]::IsInputRedirected) {
            $secure = Read-Host -AsSecureString "Password of the PostgreSQL user $env:PGUSER (not shown)"
            $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
            try { Set-TempEnv 'PGPASSWORD' ([Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)) }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
        }
        Invoke-Native { & $Psql -X -w -tA -d postgres -c 'SELECT 1' 2>&1 | Out-Null }
        if ($LASTEXITCODE -ne 0) {
            Fail "Could not connect to PostgreSQL as $env:PGUSER on ${env:PGHOST}:$env:PGPORT. Is the server running and the password right?"
        }
    }
    $may = (Invoke-Native { & $Psql -X -w -tA -d postgres -c 'SELECT rolsuper OR (rolcreaterole AND rolcreatedb) FROM pg_roles WHERE rolname = current_user' }) -join ''
    if ($may -ne 't') { Fail "The PostgreSQL user $env:PGUSER may not create users and databases. Use an administrator such as postgres (PGUSER)." }
}

# Runs SQL from a string as the administrator. psql variables (-v name=value) can be passed in $Vars.
function Invoke-AdminSql([string]$Psql, [string]$Sql, [string[]]$Vars = @()) {
    $psqlArgs = @('-X', '-w', '-q', '-v', 'ON_ERROR_STOP=1', '-d', 'postgres')
    foreach ($v in $Vars) { $psqlArgs += @('-v', $v) }
    Invoke-Native { $Sql | & $Psql @psqlArgs }
    return ($LASTEXITCODE -eq 0)
}

function Test-DbOrUserExists([string]$Psql, [string]$Name) {
    $sql = "SELECT 1 FROM pg_database WHERE datname = :'name' UNION SELECT 1 FROM pg_roles WHERE rolname = :'name';"
    $out = Invoke-Native { $sql | & $Psql -X -w -tA -v ON_ERROR_STOP=1 -d postgres -v "name=$Name" }
    if ($LASTEXITCODE -ne 0) { Fail 'Could not query PostgreSQL.' }
    return (($out -join '').Trim() -ne '')
}

# Creates a file only the current user can read, then writes the text into it (UTF-8 without a byte order mark,
# which the backend expects).
function New-SecretFile([string]$Path, [string]$Text) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    New-Item -ItemType File -Path $Path | Out-Null
    if ($OnWindows) {
        $acl = New-Object Security.AccessControl.FileSecurity
        $acl.SetAccessRuleProtection($true, $false)
        $me = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($me, 'FullControl', 'Allow')))
        Set-Acl -LiteralPath $Path -AclObject $acl
    }
    else {
        & chmod 600 $Path
        if ($LASTEXITCODE -ne 0) { Fail "Could not restrict $Path to you." }
    }
    [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding $false))
}

function Show-LogTail([string]$Path) {
    if (Test-Path -LiteralPath $Path) {
        Write-Host "Last lines of ${Path}:"
        Get-Content -LiteralPath $Path -Tail 30 | ForEach-Object { Write-Host $_ }
    }
}

# Setup hands the new API key over without printing it (KAN-34): it shows a masked form, and the person presses C to
# copy the key or S to show it. The key is never put on a command line, in a file or in a log.
function Test-Terminal { return -not ([Console]::IsInputRedirected -or [Console]::IsOutputRedirected) }

function Get-MaskedKey([string]$Key) { return $Key.Substring(0, 8) + '...' + $Key.Substring($Key.Length - 4) }

# Copying is offered on Windows only, where the copy can be kept out of clipboard history and cloud sync. It needs a
# single-threaded apartment, which Windows PowerShell (and so setup.cmd) has by default.
function Test-SecretClipboard {
    if (-not $OnWindows) { return $false }
    if ([Threading.Thread]::CurrentThread.GetApartmentState() -ne [Threading.ApartmentState]::STA) { return $false }
    try { Add-Type -AssemblyName System.Windows.Forms; return $true } catch { return $false }
}

# The three formats tell Windows not to keep the text in clipboard history (Win+V) and not to sync it to other
# devices: https://learn.microsoft.com/en-us/windows/win32/dataxchg/clipboard-formats
function Set-SecretClipboard([string]$Text) {
    $data = New-Object System.Windows.Forms.DataObject
    $data.SetData([System.Windows.Forms.DataFormats]::UnicodeText, $Text)
    foreach ($format in @('ExcludeClipboardContentFromMonitorProcessing', 'CanIncludeInClipboardHistory', 'CanUploadToCloudClipboard')) {
        $data.SetData($format, (New-Object IO.MemoryStream (, [BitConverter]::GetBytes([int32]0))))
    }
    [System.Windows.Forms.Clipboard]::SetDataObject($data, $true, 5, 100)
}

function Get-ClipboardText { return [System.Windows.Forms.Clipboard]::GetText() }

# With -ShowKey the key is printed. Otherwise it is masked, and the person chooses C (copy), S (show) or Enter
# (finish). Enter clears the clipboard if it still holds the key, and asks once more when the key was neither copied
# nor shown, because it cannot be shown again.
function Send-ApiKey([string]$Key, [bool]$ShowKey) {
    Write-Host 'API key for the demo team. It is shown only this once: keep it somewhere safe and never commit it.'
    Write-Host ''
    if ($ShowKey) {
        Write-Host "    $Key"
        Write-Host ''
        return
    }
    Write-Host "    $(Get-MaskedKey $Key) (hidden)"
    Write-Host ''
    $canCopy = Test-SecretClipboard
    $copied = $false
    $shown = $false
    $warned = $false
    while ($true) {
        if ($canCopy) { Write-Host -NoNewline '[C] copy it   [S] show it   [Enter] finish: ' }
        else { Write-Host -NoNewline '[S] show it   [Enter] finish: ' }
        $pressed = [Console]::ReadKey($true)
        Write-Host ''
        if ($pressed.Key -eq [ConsoleKey]::Enter) {
            if (-not ($copied -or $shown -or $warned)) {
                Write-Host 'The key was neither copied nor shown, and it cannot be shown again. Press Enter again to finish anyway.'
                $warned = $true
                continue
            }
            break
        }
        if ($pressed.Key -eq [ConsoleKey]::C -and $canCopy) {
            try {
                Set-SecretClipboard $Key
                $copied = ((Get-ClipboardText) -ceq $Key)
            }
            catch { $copied = $false }
            if ($copied) { Write-Host 'Copied, and kept out of clipboard history. Paste it where you keep secrets, then press Enter: that clears the clipboard.' }
            else { Write-Host 'Copying did not work. Press S to show the key instead.' }
        }
        elseif ($pressed.Key -eq [ConsoleKey]::S) {
            Write-Host ''
            Write-Host "    $Key"
            Write-Host ''
            $shown = $true
        }
    }
    if ($copied -and ((Get-ClipboardText) -ceq $Key)) {
        [System.Windows.Forms.Clipboard]::Clear()
        Write-Host 'Clipboard cleared.'
    }
    Write-Host ''
}
