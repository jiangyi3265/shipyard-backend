param([switch]$Test)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$database = if ($Test) { 'shipyard33_test' } else { 'shipyard33' }
$pidFile = Join-Path $projectRoot ".local/$database.pid"
if (Test-Path -LiteralPath $pidFile) {
    $serverPid = [int](Get-Content -LiteralPath $pidFile)
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$serverPid"
    $jarPath = Join-Path $projectRoot 'ruoyi-admin\target\ruoyi-admin.jar'
    if ($process -and $process.Name -eq 'java.exe' -and $process.CommandLine.Contains($jarPath)) {
        Stop-Process -Id $serverPid
        Write-Output "Stopped $database backend ($serverPid)"
    }
}
