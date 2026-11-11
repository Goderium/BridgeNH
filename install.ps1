param(
    [switch]$Inspect, [switch]$NonInteractive, [switch]$ConfigureOnly, [switch]$ManualLaunch,
    [ValidateSet('source', 'user')][string]$Scope,
    [string]$Config, [string]$Profile, [string]$Instance, [string]$Prism, [string]$Jar,
    [string]$PrismRoot, [string]$InstanceName,
    [string]$CodexConfig, [string]$ServerName, [string]$RuntimePython,
    [string]$Python
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$candidates = @()
if ($Python) { $candidates += $Python }
$candidates += (Join-Path $PSScriptRoot 'mcp-server\.venv\Scripts\python.exe')
if ($env:APPDATA) { $candidates += (Join-Path $env:APPDATA 'MinecraftCodexBridge\runtime\Scripts\python.exe') }
foreach ($command in @('python', 'python3', 'py')) {
    $found = Get-Command $command -ErrorAction SilentlyContinue
    if ($found -and $found.Source -notlike '*\WindowsApps\*') { $candidates += $found.Source }
}
$interpreter = $null
foreach ($candidate in ($candidates | Select-Object -Unique)) {
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
    $probe = & $candidate -c 'import sys; print(sys.executable) if sys.version_info >= (3,11) else sys.exit(1)' 2>$null
    if ($LASTEXITCODE -eq 0 -and $probe -and (Test-Path -LiteralPath $probe -PathType Leaf)) { $interpreter = $probe; break }
}
while (-not $interpreter) {
    if ($Inspect -or $NonInteractive) {
        @{status='needs_input'; questions=@(@{key='Python'; question='Install Python 3.11+ with venv/pip, or provide its python.exe path.'; choices=@()})} | ConvertTo-Json -Depth 5
        exit 2
    }
    $candidate = (Read-Host 'Python 3.11+ is required. Enter the full python.exe path (blank to stop)').Trim('"')
    if (-not $candidate) { exit 2 }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
    $probe = & $candidate -c 'import sys; print(sys.executable) if sys.version_info >= (3,11) else sys.exit(1)' 2>$null
    if ($LASTEXITCODE -eq 0 -and $probe) { $interpreter = $probe }
}
$arguments = @('-m', 'minecraft_codex_bridge.setup', '--payload', $PSScriptRoot)
foreach ($pair in @(@('Inspect','inspect'), @('NonInteractive','non-interactive'), @('ConfigureOnly','configure-only'), @('ManualLaunch','manual-launch'))) {
    if (Get-Variable -Name $pair[0] -ValueOnly) { $arguments += '--' + $pair[1] }
}
foreach ($pair in @(@('Scope','scope'), @('Config','config'), @('Profile','profile'), @('PrismRoot','prism-root'), @('InstanceName','instance-name'), @('Instance','instance'), @('Prism','prism'), @('Jar','jar'), @('CodexConfig','codex-config'), @('ServerName','server-name'), @('RuntimePython','runtime-python'))) {
    $value = Get-Variable -Name $pair[0] -ValueOnly
    if ($value) { $arguments += @(("--" + $pair[1]), $value) }
}
$oldPythonPath = $env:PYTHONPATH
$oldUtf8 = $env:PYTHONUTF8
try {
    $env:PYTHONPATH = Join-Path $PSScriptRoot 'mcp-server\src'
    $env:PYTHONUTF8 = '1'
    & $interpreter @arguments
    $resultCode = $LASTEXITCODE
} finally {
    $env:PYTHONPATH = $oldPythonPath
    $env:PYTHONUTF8 = $oldUtf8
}
exit $resultCode
