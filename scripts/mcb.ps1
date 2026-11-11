param(
    [string]$Config, [string]$Profile,
    [ValidateSet('Snapshot', 'Recording', 'Call', 'Launch', 'DeployBridge')][string]$Action = 'Snapshot',
    [string]$Tool = 'mcb_snapshot', [string]$ToolArguments = '{}', [string]$JarPath,
    [ValidateSet('in-process', 'stdio')][string]$Transport = 'in-process', [switch]$Measure
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'mcb-common.ps1')
$settings = Get-McbSettings -Config $Config -Profile $Profile
$root = Split-Path -Parent $PSScriptRoot
$keys = @('MCB_CONFIG_FILE','MCB_PROFILE','MCB_INSTANCE_PATH','MCB_RUNTIME_FILE','MCB_URL','MCB_TOKEN','MCB_PRISM_EXE','MCB_INSTANCE_ROOT','MCB_PRISM_ROOT','PYTHONPATH','PYTHONUTF8','PYTHONDONTWRITEBYTECODE')
$previous = @{}
foreach ($key in $keys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
try {
    foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $null, 'Process') }
    $env:MCB_CONFIG_FILE = $settings.configFile
    $env:MCB_PROFILE = $settings.profile
    $env:PYTHONUTF8 = '1'
    $env:PYTHONDONTWRITEBYTECODE = '1'
    if (Test-Path -LiteralPath (Join-Path $root 'build.gradle')) { $env:PYTHONPATH = Join-Path $root 'mcp-server\src' }
    $OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    switch ($Action) {
        'Launch' { & $settings.pythonExe -m minecraft_codex_bridge.launcher_cli }
        'DeployBridge' {
            if (-not $JarPath) { throw 'DeployBridge requires JarPath.' }
            & $settings.pythonExe -m minecraft_codex_bridge.setup --deploy-only --jar $JarPath --instance $settings.instancePath
        }
        default {
            if ($Action -eq 'Snapshot') { $Tool = 'mcb_snapshot'; $ToolArguments = '{}' }
            if ($Action -eq 'Recording') { $Tool = 'mcb_recording'; $ToolArguments = '{"view":"summary"}' }
            if ($Tool -notmatch '^mcb_[a-z0-9_]+$') { throw 'Call requires an MCB tool name.' }
            # Windows PowerShell 5 strips quotes in native argv; use the existing UTF-8 stdin contract.
            $arguments = @('-m', 'minecraft_codex_bridge.tool_cli', $Tool, '--args', '-', '--transport', $Transport)
            if ($Measure) { $arguments += '--measure' }
            if ($ToolArguments -eq '-') { $input | & $settings.pythonExe @arguments }
            else { $ToolArguments | & $settings.pythonExe @arguments }
        }
    }
    $resultCode = $LASTEXITCODE
} finally {
    foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
}
exit $resultCode
