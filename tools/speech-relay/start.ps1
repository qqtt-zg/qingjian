# 手机朗读连接工具；保持此终端运行，关闭后立即停止转发。
param(
    [switch]$Internet,
    [string]$Cloudflared,
    [switch]$Tailscale,
    [switch]$ResetPairing,
    [int]$TailscalePort = 8443
)

$ErrorActionPreference = 'Stop'
if ($Internet -and $Tailscale) { throw '临时公网与 Tailscale 模式只能选择一个' }
$scriptPath = Join-Path $PSScriptRoot 'server.py'
$relayArguments = @($scriptPath, '--open')
if ($Tailscale) {
    $relayArguments += @('--tailscale', '--tailscale-port', "$TailscalePort")
}
if ($ResetPairing) { $relayArguments += '--reset-pairing' }
if ($Internet) {
    if (-not $Cloudflared) {
        $bundledTool = Join-Path $PSScriptRoot '..\..\target\tools\cloudflared.exe'
        $Cloudflared = if (Test-Path -LiteralPath $bundledTool) { $bundledTool } else { 'cloudflared' }
    }
    $relayArguments += @('--internet', '--cloudflared', $Cloudflared)
}
if (Get-Command python -ErrorAction SilentlyContinue) {
    & python @relayArguments
} elseif (Get-Command py -ErrorAction SilentlyContinue) {
    & py -3 @relayArguments
} else {
    throw '需要 Python 3.10 或以上版本，请安装后重试。'
}
exit $LASTEXITCODE
