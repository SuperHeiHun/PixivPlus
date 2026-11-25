<#
.SYNOPSIS
    使用 JDK17 和独立输出目录构建；日志重定向，回归通过后安装到指定设备。
.DESCRIPTION
    默认执行 Debug、单元测试与 lint；-Full 额外构建 Release。
    复用 Gradle daemon，空闲 10 分钟后退出；保留单 worker 与 Kotlin in-process。
    不提交、推送、发布或修改 LSPosed DB。
#>
[CmdletBinding()]
param(
    [switch]$Full,
    [switch]$Profile,
    [switch]$NoInstall,
    [string]$JavaHome = "",
    [string]$SdkRoot = "",
    [string]$Device = "emulator-5554",
    [string]$Log = "build-latest.log"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$originalLocation = Get-Location
$originalJava = $env:JAVA_HOME
$originalSdk = $env:ANDROID_HOME
$mutex = $null
$locked = $false

function Get-JavaMajor([string]$JavaRoot) {
    $exe = Join-Path $JavaRoot "bin/java.exe"
    if (-not (Test-Path -LiteralPath $exe)) { return 0 }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $text = (& $exe -version 2>&1 | Out-String)
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -eq 0 -and $text -match 'version\s+"(\d+)') { return [int]$Matches[1] }
    return 0
}

function Invoke-Adb([string[]]$Arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $text = (& $script:adb -s $Device @Arguments 2>&1 | Out-String)
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) { throw "ADB 操作失败：$text" }
    return $text
}

try {
    Set-Location -LiteralPath $root
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $key = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($root.ToLowerInvariant())))).Replace("-", "")
    } finally { $sha.Dispose() }
    $mutex = [Threading.Mutex]::new($false, "Local\PixivPlusBuild-$key")
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (-not $locked) { throw "本仓库已有构建脚本在运行，请等待结束，不要并发写入输出目录。" }

    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        $candidates = @($env:JAVA_HOME)
        $javaDirectory = Join-Path $env:ProgramFiles "Java"
        if (Test-Path -LiteralPath $javaDirectory) {
            $candidates += @(Get-ChildItem -LiteralPath $javaDirectory -Directory | Where-Object { $_.Name -match '17' } |
                Sort-Object Name -Descending | ForEach-Object { $_.FullName })
        }
        foreach ($candidate in $candidates) {
            if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Get-JavaMajor $candidate) -eq 17) {
                $JavaHome = $candidate
                break
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($JavaHome) -or (Get-JavaMajor $JavaHome) -ne 17) {
        throw "需要 JDK17，请使用 -JavaHome 指定安装目录。"
    }
    if ([string]::IsNullOrWhiteSpace($SdkRoot)) {
        $sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
        if (Test-Path "local.properties") {
            $line = Get-Content "local.properties" | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
            if ($line) { $sdkCandidates += $line.Substring(8).Replace('\:', ':').Replace('\\', '\') }
        }
        foreach ($candidate in $sdkCandidates) {
            if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
                $SdkRoot = $candidate
                break
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($SdkRoot) -or -not (Test-Path -LiteralPath $SdkRoot)) {
        throw "找不到 Android SDK，请使用 -SdkRoot 指定目录。"
    }
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $SdkRoot
    $script:adb = Join-Path $SdkRoot "platform-tools/adb.exe"
    if (-not $NoInstall -and -not (Test-Path -LiteralPath $script:adb)) { throw "找不到 adb.exe。" }

    $arguments = @("--offline", "--console=plain", "--daemon", "--no-watch-fs", "--max-workers=1",
        "-Dorg.gradle.daemon.idletimeout=600000", "-Pplp.buildDirectory=build-i18n-verify",
        "-Pkotlin.compiler.execution.strategy=in-process")
    $tools = Join-Path $SdkRoot "build-tools"
    if (Test-Path -LiteralPath $tools) {
        $aapt = Get-ChildItem -LiteralPath $tools -Directory | Where-Object {
            $_.Name -match '^\d+\.\d+\.\d+$' -and (Test-Path (Join-Path $_.FullName "aapt2.exe"))
        } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
        if ($aapt) { $arguments += "-Pandroid.aapt2FromMavenOverride=$(Join-Path $aapt.FullName 'aapt2.exe')" }
    }
    if ($Profile) { $arguments += "--profile" }
    $arguments += @("testDebugUnitTest", "assembleDebug")
    if ($Full) { $arguments += "assembleRelease" }
    $arguments += "lintDebug"

    Write-Host "开始构建（JDK17 / daemon / 单 worker）；日志：$Log"
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & (Join-Path $root "gradlew.bat") @arguments 2>&1 | Out-File -FilePath $Log -Encoding utf8 -Width 4096
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) {
        Get-Content -LiteralPath $Log -Tail 40 | ForEach-Object { Write-Host $_ }
        throw "构建失败（退出码 $code），未安装。"
    }
    [xml]$lint = Get-Content "app/build-i18n-verify/reports/lint-results-debug.xml" -Raw
    if (@($lint.issues.issue | Where-Object { $_.severity -in @("Error", "Fatal") }).Count -gt 0) {
        throw "lint 存在错误，未安装。"
    }
    $tests = 0
    $reports = @(Get-ChildItem "app/build-i18n-verify/test-results/testDebugUnitTest/TEST-*.xml")
    if ($reports.Count -eq 0) { throw "未找到单元测试结果，未安装。" }
    foreach ($report in $reports) {
        [xml]$suite = Get-Content -LiteralPath $report.FullName -Raw
        if ([int]$suite.testsuite.failures -gt 0 -or [int]$suite.testsuite.errors -gt 0) {
            throw "单元测试失败，未安装。"
        }
        $tests += [int]$suite.testsuite.tests
    }
    Get-Content -LiteralPath $Log -Tail 5 | ForEach-Object { Write-Host $_ }
    Write-Host "回归通过：$tests 项测试，lint 无错误。"
    if (-not $NoInstall) {
        $state = (Invoke-Adb @("get-state")).Trim()
        if ($state -ne "device") { throw "设备 $Device 不可用，包已构建但未安装。" }
        $apkDirectory = "app/build-i18n-verify/outputs/apk/debug"
        $metadata = Get-Content "$apkDirectory/output-metadata.json" -Raw | ConvertFrom-Json
        $apk = Join-Path $apkDirectory $metadata.elements[0].outputFile
        $install = Invoke-Adb @("install", "-r", $apk)
        if ($install -notmatch '\bSuccess\b') { throw "未确认安装成功：$install" }
        $package = Invoke-Adb @("shell", "dumpsys", "package", "com.heihun.pixivplus")
        $expectedCode = $metadata.elements[0].versionCode
        $expectedName = [regex]::Escape($metadata.elements[0].versionName)
        if ($package -notmatch "versionCode=$expectedCode\b" -or $package -notmatch "versionName=$expectedName(?:\s|$)") {
            throw "安装后的版本与构建产物不一致，不继续重启宿主。"
        }
        Invoke-Adb @("shell", "am", "force-stop", "jp.pxv.android") | Out-Null
        $launch = Invoke-Adb @("shell", "am", "start", "-W", "-n", "jp.pxv.android/.activity.RoutingActivity")
        if ($launch -notmatch 'Status:\s*ok') { throw "Pixiv 启动未确认成功：$launch" }
        Write-Host "已安装 $($metadata.elements[0].versionName)（$expectedCode）到 $Device，并仅重启 Pixiv。"
    }
} finally {
    $env:JAVA_HOME = $originalJava
    $env:ANDROID_HOME = $originalSdk
    Set-Location $originalLocation
    if ($locked) { $mutex.ReleaseMutex() }
    if ($mutex) { $mutex.Dispose() }
}
