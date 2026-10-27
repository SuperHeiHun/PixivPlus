<#
.SYNOPSIS
    构建、提交并推送 PixivLocalPlus 到 GitHub。

.DESCRIPTION
    默认流程：读取 app/build.gradle.kts 中的版本号 -> 运行单元测试 / lint / 调试包与发布包构建
    -> 提交全部改动 -> 推送到 origin/main。

    加上 -Release 时，额外创建并推送 v<版本号> 标签，触发 .github/workflows/release.yml
    自动构建发布包并创建 GitHub Release。

.PARAMETER Version
    覆盖要推送的版本号（默认读取 app/build.gradle.kts 的 versionName）。

.PARAMETER Release
    推送 v<版本号> 标签以触发 GitHub Release。

.PARAMETER SkipBuild
    跳过构建与测试，仅提交并推送。

.PARAMETER Proxy
    Git 使用的 HTTP 代理。留空时自动探测本地 127.0.0.1:7890，探测失败则直连。

.PARAMETER Message
    提交信息（默认按版本号自动生成）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\push.ps1 -Release
#>
[CmdletBinding()]
param(
    [string]$Version = "",
    [switch]$Release,
    [switch]$SkipBuild,
    [string]$Proxy = "",
    [string]$Message = ""
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)

function Write-Step([string]$Text) {
    Write-Host ""
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Invoke-Checked([string]$Command, [string[]]$Arguments, [string]$What) {
    Write-Host "    $Command $($Arguments -join ' ')" -ForegroundColor DarkGray
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $Command @Arguments
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) {
        throw "$What 失败（退出码 $code）"
    }
}

# ---------------------------------------------------------------- 版本号
if ([string]::IsNullOrWhiteSpace($Version)) {
    $gradleFile = Join-Path (Get-Location) "app\build.gradle.kts"
    $match = Select-String -Path $gradleFile -Pattern 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
    if (-not $match) {
        throw "无法从 app/build.gradle.kts 解析 versionName，请使用 -Version 手动指定"
    }
    $Version = $match.Matches[0].Groups[1].Value
}
Write-Host "版本号: $Version" -ForegroundColor Green

# ---------------------------------------------------------------- 代理
if ([string]::IsNullOrWhiteSpace($Proxy)) {
    $proxyPort = 7890
    try {
        $probe = New-Object System.Net.Sockets.TcpClient
        $probe.Connect("127.0.0.1", $proxyPort)
        $probe.Close()
        $Proxy = "http://127.0.0.1:$proxyPort"
        Write-Host "使用本地代理: $Proxy" -ForegroundColor Green
    } catch {
        Write-Host "未探测到本地代理 $proxyPort，使用直连" -ForegroundColor Yellow
    }
} else {
    Write-Host "使用代理: $Proxy" -ForegroundColor Green
}

# ---------------------------------------------------------------- 构建与测试
if (-not $SkipBuild) {
    Write-Step "运行测试与构建"
    $gradleLog = Join-Path (Get-Location) "build.log"
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    cmd /c "gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease > build.log 2>&1"
    $gradleCode = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($gradleCode -ne 0) {
        Get-Content $gradleLog -Tail 40 | ForEach-Object { Write-Host $_ -ForegroundColor Red }
        throw "构建失败，日志见 $gradleLog"
    }
    Get-Content $gradleLog -Tail 3 | ForEach-Object { Write-Host $_ -ForegroundColor DarkGray }
}

# ---------------------------------------------------------------- 提交
Write-Step "提交改动"
Invoke-Checked "git" @("add", "-A") "git add"
$status = git status --short
if ([string]::IsNullOrWhiteSpace(($status | Out-String).Trim())) {
    Write-Host "    没有需要提交的改动" -ForegroundColor Yellow
} else {
    if ([string]::IsNullOrWhiteSpace($Message)) {
        $Message = "release: PixivLocalPlus $Version"
    }
    Invoke-Checked "git" @("commit", "-m", $Message) "git commit"
}

# ---------------------------------------------------------------- 推送
Write-Step "推送到 origin/main"
$gitPushArgs = @()
if (-not [string]::IsNullOrWhiteSpace($Proxy)) {
    $gitPushArgs += @("-c", "http.proxy=$Proxy")
}
Invoke-Checked "git" ($gitPushArgs + @("push", "origin", "main")) "git push"

# ---------------------------------------------------------------- 发布
if ($Release) {
    Write-Step "推送标签 v$Version（触发 GitHub Release）"
    $tag = "v$Version"
    if (git tag -l $tag) {
        Write-Host "    标签 $tag 已存在，跳过创建" -ForegroundColor Yellow
    } else {
        Invoke-Checked "git" @("tag", "-a", $tag, "-m", "PixivLocalPlus $Version") "git tag"
    }
    Invoke-Checked "git" ($gitPushArgs + @("push", "origin", $tag)) "git push tag"
    Write-Host "标签已推送，请关注 GitHub Actions 发布进度：" -ForegroundColor Green
    Write-Host "    https://github.com/SuperHeiHun/PixivPlus/actions" -ForegroundColor Green
}

Write-Host ""
Write-Host "完成: PixivLocalPlus $Version 已推送到 GitHub" -ForegroundColor Green
