<#
Removes what setup.ps1 created (KAN-26): the database, its user and the settings file. Double-click remove.cmd, or run:

    demo\remove.ps1 [-DbName NAME] [-EnvFile PATH] [-ConfirmDbName NAME]

Every key pair stored in that database is lost. Stop the demo first. Defaults as in setup.ps1.
It asks you to type the database name, unless -ConfirmDbName gives it.
The settings file is deleted only when it points at that database. macOS and Linux: demo/remove.sh does the same.
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
    $psql = Find-Psql
    Connect-Admin $psql
    if (-not $ConfirmDbName) {
        Write-Host "This deletes the database $DbName, its user and every key pair stored in it."
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

    if (Test-Path -LiteralPath $EnvFile) {
        $pointsHere = [IO.File]::ReadAllLines($EnvFile) | Where-Object { $_ -cmatch "^DB_URL=jdbc:postgresql://[^/]+/$DbName\s*$" }
        if ($pointsHere) {
            Remove-Item -LiteralPath $EnvFile -Force
            Write-Host "Deleted $EnvFile."
        }
        else {
            Write-Host "Kept ${EnvFile}: it does not point at the database $DbName."
        }
    }
}
catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    $code = 1
}
finally {
    Restore-Env
}
exit $code
