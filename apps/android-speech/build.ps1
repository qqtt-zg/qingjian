# 使用 JDK 17、Android SDK 36 和 Gradle 8.13 构建个人试用 APK。
param([string]$Abi = 'arm64-v8a', [switch]$Prepare, [switch]$PublishUpdate)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ($Abi -notin @('arm64-v8a','x86_64')) { throw '不支持的 ABI' }
if ($Prepare) {
    & python (Join-Path $PSScriptRoot 'prepare.py')
    if ($LASTEXITCODE -ne 0) { throw '依赖准备失败' }
}
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem (Join-Path $repoRoot '.local\tools\jdk17') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
    else { throw '请设置 JAVA_HOME 指向 JDK 17' }
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$gradleCommand = Get-Command gradle -ErrorAction SilentlyContinue
$gradlePath = if ($gradleCommand) { $gradleCommand.Source } else {
    Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.13-bin') -Recurse -Filter gradle.bat -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $gradlePath) { throw '需要 Gradle 8.13，可安装后加入 PATH' }
$proxyArguments = @()
if ($env:HTTPS_PROXY) {
    $proxyUri = [Uri]$env:HTTPS_PROXY
    $proxyArguments = @("-Dhttps.proxyHost=$($proxyUri.Host)","-Dhttps.proxyPort=$($proxyUri.Port)","-Dhttp.proxyHost=$($proxyUri.Host)","-Dhttp.proxyPort=$($proxyUri.Port)")
}
& $gradlePath -p $PSScriptRoot :app:assembleDebug "-PspeechAbi=$Abi" --console=plain @proxyArguments
if ($LASTEXITCODE -ne 0) { throw 'Android 构建失败' }
$buildConfig = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'app/build.gradle') -Raw
$versionCode = [regex]::Match($buildConfig, 'versionCode\s+(\d+)').Groups[1].Value
$versionName = [regex]::Match($buildConfig, "versionName\s+'([^']+)'").Groups[1].Value
$artifactDir = Join-Path $repoRoot 'dist\android'
New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null
$artifactPath = Join-Path $artifactDir "qingjian-learning-$versionName-build$versionCode-$Abi.apk"
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination $artifactPath -Force
Write-Output $artifactPath
if ($PublishUpdate) {
    if ($Abi -ne 'arm64-v8a') { throw '仅 ARM64 包可发布到手机更新通道' }
    & python (Join-Path $PSScriptRoot '../../tools/speech-relay/app_updates.py') $artifactPath $versionCode $versionName
    if ($LASTEXITCODE -ne 0) { throw '手机更新包发布失败' }
}
