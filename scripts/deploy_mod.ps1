param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [Parameter(Mandatory = $true)][string]$InstancePath
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'mcb-common.ps1')
$settings = Get-McbSettings
$oldPath = $env:PYTHONPATH
try {
    $env:PYTHONPATH = Join-Path (Split-Path -Parent $PSScriptRoot) 'mcp-server/src'
    & $settings.pythonExe -m minecraft_codex_bridge.setup --deploy-only --jar $JarPath --instance $InstancePath
    $resultCode = $LASTEXITCODE
} finally { $env:PYTHONPATH = $oldPath }
exit $resultCode
