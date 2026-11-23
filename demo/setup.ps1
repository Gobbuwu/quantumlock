<#
Sets up QuantumLock for a demo on this computer, once (KAN-26). Double-click setup.cmd, or run:

    demo\setup.ps1 [-DbName NAME] [-EnvFile PATH]

Creates the PostgreSQL database and its user with a random password, loads database\schema.sql and seed.sql,
writes backend\.env with that password and a random MASTER_KEY, builds the backend and issues the first API key
for the demo team. The key is shown once. The database password and MASTER_KEY are never shown.
It refuses to run when the settings file, the database or the user already exists, so it never overwrites
anything. If a step fails, everything it created is removed again.

NAME is the database and user name (default quantumlock). PATH is the settings file (default backend\.env).
PostgreSQL administrator: PGHOST, PGPORT, PGUSER (defaults localhost, 5432, postgres) and PGPASSWORD,
which is asked for when it is not set. macOS and Linux: demo/setup.sh does the same.
#>
param(
    [string]$DbName = 'quantumlock',
    [string]$EnvFile = ''
)
. (Join-Path $PSScriptRoot 'common.ps1')

$createdDb = $false
$wroteEnv = $false
$code = 0
try {
    Test-DbName $DbName
    if ($EnvFile) { $EnvFile = Get-FullPath $EnvFile } else { $EnvFile = Join-Path $Root 'backend\.env' }

    Step 'Check the tools'
    $java = Find-Java
    Find-Node | Out-Null
    $psql = Find-Psql
    if (Test-Path -LiteralPath $EnvFile) {
        Fail "$EnvFile already exists. Setup never overwrites it. Use -EnvFile for another file, or remove.ps1 first."
    }
    Connect-Admin $psql
    if (Test-DbOrUserExists $psql $DbName) {
        Fail "A database or user named $DbName already exists. Setup never overwrites it. Use -DbName for another name, or remove.ps1 first."
    }
    Write-Host "Java, Node.js and PostgreSQL ($env:PGUSER on ${env:PGHOST}:$env:PGPORT) are ready."

    Step "Create the database $DbName"
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 24
    $rng.GetBytes($bytes)
    $dbPassword = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    $bytes = New-Object byte[] 32
    $rng.GetBytes($bytes)
    $masterKey = [Convert]::ToBase64String($bytes)
    $rng.Dispose()
    Mask $dbPassword
    Mask $masterKey
    $createdDb = $true
    # The password reaches psql through its environment, not the command line, so other programs cannot see it.
    Set-TempEnv 'QL_NEW_DB_PASSWORD' $dbPassword
    $sql = "\getenv pw QL_NEW_DB_PASSWORD`nCREATE ROLE :`"db`" LOGIN PASSWORD :'pw';`nCREATE DATABASE :`"db`" OWNER :`"db`";"
    $ok = Invoke-AdminSql $psql $sql @("db=$DbName")
    Set-TempEnv 'QL_NEW_DB_PASSWORD' ''
    if (-not $ok) { Fail "Could not create the database $DbName." }
    $adminUser = $env:PGUSER
    $adminPassword = $env:PGPASSWORD
    Set-TempEnv 'PGUSER' $DbName
    Set-TempEnv 'PGPASSWORD' $dbPassword
    try {
        foreach ($file in @('schema.sql', 'seed.sql')) {
            Invoke-Native { & $psql -X -w -q -v ON_ERROR_STOP=1 -d $DbName -f (Join-Path $Root "database\$file") }
            if ($LASTEXITCODE -ne 0) { Fail "Loading database\$file failed." }
        }
    }
    finally {
        Set-TempEnv 'PGUSER' $adminUser
        Set-TempEnv 'PGPASSWORD' $adminPassword
    }
    Write-Host 'Tables and demo data loaded.'

    Step "Write $EnvFile"
    $dbUrl = "jdbc:postgresql://${env:PGHOST}:$env:PGPORT/$DbName"
    $nl = [Environment]::NewLine
    $text = "# Written by demo\setup.ps1 on $([DateTime]::UtcNow.ToString('yyyy-MM-dd')). Local secrets: never commit or share this file.$nl" +
        "# MASTER_KEY encrypts every stored private key. Keep a copy somewhere safe: without it they cannot be used.$nl" +
        "DB_URL=$dbUrl${nl}DB_USERNAME=$DbName${nl}DB_PASSWORD=$dbPassword${nl}MASTER_KEY=$masterKey$nl"
    $wroteEnv = $true
    New-SecretFile $EnvFile $text
    Write-Host 'Written, readable by you only.'

    Step 'Build the backend and issue the first API key'
    $jar = Build-Backend
    # Run from demo\.run so no other .env file is picked up, with the new settings in the environment.
    Set-TempEnv 'DB_URL' $dbUrl
    Set-TempEnv 'DB_USERNAME' $DbName
    Set-TempEnv 'DB_PASSWORD' $dbPassword
    Set-TempEnv 'MASTER_KEY' $masterKey
    Push-Location $RunDir
    try { $out = Invoke-Native { & $java -jar $jar issue-api-key 2>&1 | ForEach-Object { "$_" } } }
    finally { Pop-Location }
    $issueCode = $LASTEXITCODE
    foreach ($name in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'MASTER_KEY')) { Set-TempEnv $name '' }
    $match = [regex]::Match(($out -join "`n"), 'ql_test_[A-Za-z0-9_-]{43}')
    if ($issueCode -ne 0 -or -not $match.Success) {
        $out | Select-Object -Last 30 | ForEach-Object { Write-Host $_ }
        Fail "Issuing the API key failed (exit code $issueCode)."
    }
    $apiKey = $match.Value
    $out = $null
    Mask $apiKey

    $createdDb = $false
    $wroteEnv = $false
    Step 'Done'
    Write-Host "The database $DbName and $EnvFile are ready."
    Write-Host ''
    Write-Host 'API key for the demo team. It is shown only this once: copy it somewhere safe and never commit it.'
    Write-Host ''
    Write-Host "    $apiKey"
    Write-Host ''
    $next = 'demo/start.ps1'
    if ($OnWindows) { $next = 'demo\start.cmd' }
    if ($EnvFile -ne (Join-Path $Root 'backend\.env')) { $next += " -EnvFile `"$EnvFile`"" }
    Write-Host "Next: run $next, open the dashboard address it prints and paste the key."
}
catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    $code = 1
}
finally {
    # Also runs after Ctrl+C.
    if ($wroteEnv -and (Test-Path -LiteralPath $EnvFile)) { Remove-Item -LiteralPath $EnvFile -Force }
    if ($createdDb) {
        $null = Invoke-AdminSql $psql "DROP DATABASE IF EXISTS :`"db`";`nDROP ROLE IF EXISTS :`"db`";" @("db=$DbName")
    }
    if ($createdDb -or $wroteEnv) {
        Write-Host 'Setup failed. What it had created was removed again, so it can simply be run again.'
        $code = 1
    }
    Restore-Env
}
exit $code
