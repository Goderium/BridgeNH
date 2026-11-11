# Bootstrap only. Profile validation remains in the shared Python configuration module.
function Get-McbSettings {
    param([string]$Config, [string]$Profile)
    $OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    $root = Split-Path -Parent $PSScriptRoot
    if (-not $Config) { $Config = $env:MCB_CONFIG_FILE }
    if (-not $Config -and (Test-Path -LiteralPath (Join-Path $root '.mcb.local.json'))) { $Config = Join-Path $root '.mcb.local.json' }
    if (-not $Config) { $Config = Join-Path $env:APPDATA 'MinecraftCodexBridge\config.json' }
    $Config = (Resolve-Path -LiteralPath $Config).Path
    $raw = Get-Content -LiteralPath $Config -Raw -Encoding UTF8 | ConvertFrom-Json
    $python = $raw.installation.pythonExe
    if (-not $python) { $python = Join-Path $root 'mcp-server\.venv\Scripts\python.exe' }
    if (-not [IO.Path]::IsPathRooted($python)) { $python = Join-Path (Split-Path -Parent $Config) $python }
    if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { throw 'MCB Python runtime is missing. Run install.ps1.' }
    $arguments = @('-m', 'minecraft_codex_bridge.setup', '--show-config', '--config', $Config)
    if ($Profile) { $arguments += @('--profile', $Profile) }
    $oldPath = $env:PYTHONPATH
    try {
        if (Test-Path -LiteralPath (Join-Path $root 'build.gradle')) { $env:PYTHONPATH = Join-Path $root 'mcp-server\src' }
        $result = & $python @arguments
        if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
        $settings = ($result -join "`n") | ConvertFrom-Json
        $settings.pythonExe = $python
        return $settings
    } finally { $env:PYTHONPATH = $oldPath }
}
