# 手机朗读连接工具；保持此终端运行，关闭后立即停止转发。
param(
    [switch]$Notes,
    [switch]$Internet,
    [switch]$Named,
    [string]$NamedHost,
    [string]$NamedToken,
    [string]$Cloudflared,
    [switch]$Tailscale,
    [switch]$ResetPairing,
    [int]$TailscalePort = 8443
)

$ErrorActionPreference = 'Stop'
if ($Notes) {
    if ($Tailscale -or $ResetPairing -or $Cloudflared -or $Named) { throw '-Notes 使用桌面临时 HTTPS 连接；其他模式请先启动再运行 desktop.py notes-enable' }
    $notesHelper = Join-Path $PSScriptRoot 'desktop.py'
    if (Get-Command python -ErrorAction SilentlyContinue) { & python -B $notesHelper start-notes }
    elseif (Get-Command py -ErrorAction SilentlyContinue) { & py -3 $notesHelper start-notes }
    else { throw '中文笔记工作流需要 Python 3.11 或以上版本' }
    exit $LASTEXITCODE
}
$modeCount = [int][bool]$Internet + [int][bool]$Tailscale + [int][bool]$Named
if ($modeCount -gt 1) { throw '临时公网 (-Internet)、命名隧道 (-Named) 与 Tailscale (-Tailscale) 模式只能选择一个' }
$scriptPath = Join-Path $PSScriptRoot 'server.py'
$relayArguments = @($scriptPath, '--open')
if ($Tailscale) {
    $relayArguments += @('--tailscale', '--tailscale-port', "$TailscalePort")
}
if ($ResetPairing) { $relayArguments += '--reset-pairing' }
if ($Named) {
    if (-not $NamedHost -or -not $NamedToken) { throw '-Named 模式需要指定 -NamedHost 与 -NamedToken 参数' }
    if (-not $Cloudflared) {
        $bundledTools = @(
            (Join-Path $PSScriptRoot '..\..\.local\tools\cloudflared.exe'),
            (Join-Path $PSScriptRoot '..\cloudflared.exe')
        )
        $Cloudflared = $bundledTools | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
        if (-not $Cloudflared) { $Cloudflared = 'cloudflared' }
    }
    $relayArguments += @('--named', '--named-host', $NamedHost, '--named-token', $NamedToken, '--cloudflared', $Cloudflared)
}
if ($Internet) {
    if (-not $Cloudflared) {
        $bundledTools = @(
            (Join-Path $PSScriptRoot '..\..\.local\tools\cloudflared.exe'),
            (Join-Path $PSScriptRoot '..\cloudflared.exe')
        )
        $Cloudflared = $bundledTools | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
        if (-not $Cloudflared) { $Cloudflared = 'cloudflared' }
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
