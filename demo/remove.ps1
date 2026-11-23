<#
Removes what setup.ps1 created (KAN-26): the database, its user and the settings file. Double-click remove.cmd, or run:

    demo\remove.ps1 [-DbName NAME] [-EnvFile PATH] [-ConfirmDbName NAME]

Every key pair stored in that database is lost. Stop the demo first. Defaults as in setup.ps1.
It removes only what setup created: the settings file must exist and name that database and user (DB_URL and
DB_USERNAME), so a database set up by hand is never touched. It asks you to type the database name, unless
-ConfirmDbName gives it. macOS and Linux: demo/remove.sh does the same.
#>
param(
    [string]$DbName = 'quantumlock',
    [string]$EnvFile = '',
    [string]$ConfirmDbName = ''
)
. (Join-Path $PSScriptRoot 'common.ps1')

$code = 0
try {
    Test-DbName $DbName
    if ($EnvFile) { $EnvFile = Get-FullPath $EnvFile } else { $EnvFile = Join-Path $Root 'backend\.env' }
    if (-not (Test-Path -LiteralPath $EnvFile)) {
        Fail "$EnvFile does not exist. Remove only deletes a database that setup created, with its settings file. Nothing was removed."
    }
    $lines = [IO.File]::ReadAllLines($EnvFile)
    $urlMatches = $lines | Where-Object { $_ -cmatch "^DB_URL=jdbc:postgresql://[^/]+/$DbName\s*$" }
    $userMatches = $lines | Where-Object { $_ -cmatch "^DB_USERNAME=$DbName\s*$" }
    if (-not $urlMatches -or -not $userMatches) {
        Fail "$EnvFile does not point at the database and user $DbName, so setup did not create them together. Nothing was removed."
    }
    $psql = Find-Psql
    Connect-Admin $psql
    if (-not $ConfirmDbName) {
        Write-Host "This deletes the database $DbName, its user, every key pair stored in it and $EnvFile."
        $ConfirmDbName = Read-Host 'Type the database name to confirm'
    }
    if ($ConfirmDbName -cne $DbName) { Fail 'The name did not match. Nothing was removed.' }

    if (Test-DbOrUserExists $psql $DbName) {
        $ok = Invoke-AdminSql $psql "DROP DATABASE IF EXISTS :`"db`";`nDROP ROLE IF EXISTS :`"db`";" @("db=$DbName")
        if (-not $ok) { Fail "Could not remove $DbName. Is the demo still running? Stop it and try again." }
        Write-Host "Removed the database and user $DbName."
    }
    else {
        Write-Host "There is no database or user named $DbName."
    }
    Remove-Item -LiteralPath $EnvFile -Force
    Write-Host "Deleted $EnvFile."
}
catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    $code = 1
}
finally {
    Restore-Env
}
exit $code
