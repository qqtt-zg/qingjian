# 使用项目独立的 AVD 目录，避免清理 Rust 构建缓存时丢失模拟器数据。
param([string]$Name = 'QingjianLearning', [switch]$List, [switch]$Headless, [switch]$ColdBoot)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$emulatorPath = Join-Path $sdkRoot 'emulator\emulator.exe'
if (-not (Test-Path -LiteralPath $emulatorPath -PathType Leaf)) { throw '未找到 Android SDK Emulator' }
$previousAvdHome = $env:ANDROID_AVD_HOME
try {
    $env:ANDROID_AVD_HOME = Join-Path $repoRoot '.local\android-avd'
    if ($List) {
        & $emulatorPath -list-avds
    } else {
        $launchArguments = @('-avd', $Name)
        if ($Headless) { $launchArguments += @('-no-window', '-no-audio') }
        if ($ColdBoot) { $launchArguments += '-no-snapshot-load' }
        & $emulatorPath @launchArguments
    }
    if ($LASTEXITCODE -ne 0) { throw "模拟器退出码：$LASTEXITCODE" }
} finally {
    $env:ANDROID_AVD_HOME = $previousAvdHome
}
