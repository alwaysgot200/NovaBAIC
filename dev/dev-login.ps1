<#
.SYNOPSIS
    好参谋测试环境免登录：换取 token 并以 debugToken 注入会话（仅限 192.168.1.27）。

.DESCRIPTION
    仅用于开发/功能测试。生产构建（SERVER_ORIGIN 为 https）下 App 会忽略 debugToken，
    不会留下任何绕过登录门的入口。

    流程：
      1. 读取 server-config.properties 的 server.origin.test（可用 -Origin 覆盖）；
      2. POST <origin>/api/v1/auth/login（clientId=haocanmou）换取 access_token；
      3. adb force-stop 后带 --es debugToken 冷启动 MainActivity，由
         AccountManager.debugInjectFromIntent 落盘会话并消费装包登录门；
      4. 读 float_account 偏好确认 username / expire_at 已写入。

.EXAMPLE
    .\dev\dev-login.ps1                          # 默认 ruixiwu@163.com 注入已安装的 debug 包
    .\dev\dev-login.ps1 -Email ricky.ruixiwu@gmail.com
    .\dev\dev-login.ps1 -Install                 # 先覆盖安装 app-debug.apk 再注入
    .\dev\dev-login.ps1 -Reset                   # 清除 App 数据（回到正常登录页）
#>
param(
    [string]$Email = 'ruixiwu@163.com',
    [string]$Password = '1qaz2WSX',
    [string]$Origin = '',
    [string]$Serial = '',
    [switch]$Install,
    [switch]$NoStart,
    [switch]$Reset
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Package = 'com.verlintas.baic2'
$Activity = "$Package/.MainActivity"

# ---------- adb 定位与设备选择 ----------
$Adb = 'adb'
foreach ($sdk in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, 'D:\programfiles\Android\SDK')) {
    if ([string]::IsNullOrWhiteSpace($sdk)) { continue }
    $candidate = Join-Path $sdk 'platform-tools\adb.exe'
    if (Test-Path $candidate) { $Adb = $candidate; break }
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    $devices = & $Adb devices | Select-String '^\S+\s+device$' | ForEach-Object { ($_ -split '\s+')[0] }
    $real = @($devices | Where-Object { $_ -notmatch '^emulator-' })
    $Serial = if ($real.Count -gt 0) { $real[0] } else { @($devices)[0] }
}
if ([string]::IsNullOrWhiteSpace($Serial)) { throw '没有已连接的 adb 设备，请先连接真机/启动模拟器' }
$AdbArgs = @('-s', $Serial)

Write-Host "[dev-login] 设备 $Serial" -ForegroundColor Cyan

if ($Reset) {
    & $Adb @AdbArgs shell pm clear $Package | Out-Null
    Write-Host "[dev-login] 已清除 $Package 数据（免登录态失效）" -ForegroundColor Yellow
    return
}

# ---------- 测试环境 origin（硬校验） ----------
if ([string]::IsNullOrWhiteSpace($Origin)) {
    $conf = Join-Path $Root 'server-config.properties'
    if (-not (Test-Path $conf)) { throw "缺少 $conf，无法读取测试环境地址" }
    $line = Get-Content $conf | Where-Object { $_ -match '^\s*server\.origin\.test\s*=' } | Select-Object -First 1
    if (-not $line) { throw 'server-config.properties 缺少 server.origin.test' }
    $Origin = ($line -split '=', 2)[1].Trim()
}
$Origin = $Origin.TrimEnd('/')

# 铁律：免登录只允许测试环境，生产域名/其他地址一律拒绝
if ($Origin -notmatch '^http://192\.168\.1\.27(:\d+)?$') {
    throw "拒绝执行：当前 origin=$Origin，免登录脚本仅允许 http://192.168.1.27 测试环境"
}

# ---------- 可选：覆盖安装 ----------
if ($Install) {
    $apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $apk)) { throw "未找到 $apk，请先 .\build-client.ps1 build" }
    Write-Host "[dev-login] 安装 $apk" -ForegroundColor Cyan
    & $Adb @AdbArgs install -r $apk | Out-String | Write-Host
    if ($LASTEXITCODE -ne 0) { throw 'adb install 失败' }
}

# ---------- 登录取 token ----------
$body = @{
    username  = $Email
    password  = $Password
    clientId  = 'haocanmou'
    grantType = 'password'
} | ConvertTo-Json

Write-Host "[dev-login] 登录 $Origin/api/v1/auth/login ($Email)" -ForegroundColor Cyan
try {
    $resp = Invoke-RestMethod -Method Post -Uri "$Origin/api/v1/auth/login" `
        -ContentType 'application/json' -Body $body
} catch {
    $detail = $_.ErrorDetails.Message
    if (-not $detail) { $detail = $_.Exception.Message }
    throw "登录请求失败：$detail"
}
if ($null -ne $resp.code -and [int]$resp.code -ne 200) {
    throw "登录失败：code=$($resp.code) msg=$($resp.msg)"
}
$token = $resp.data.access_token
if ([string]::IsNullOrWhiteSpace($token)) { throw '登录响应缺少 access_token' }

$expireIn = 0
if ($null -ne $resp.data.expire_in) { $expireIn = [long]$resp.data.expire_in }
if ($expireIn -le 0) { $expireIn = 30L * 24 * 60 * 60 }
$expireAt = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() + $expireIn

Write-Host ("[dev-login] token={0}... 有效期 {1} 秒，到期 {2}" -f `
    $token.Substring(0, [Math]::Min(12, $token.Length)), $expireIn, `
    ([DateTimeOffset]::FromUnixTimeSeconds($expireAt).ToLocalTime())) -ForegroundColor Green

if ($NoStart) {
    Write-Host '[dev-login] -NoStart：未启动 App' -ForegroundColor Yellow
    return
}

# ---------- debugToken 注入冷启动 ----------
& $Adb @AdbArgs shell am force-stop $Package | Out-Null
$startArgs = @(
    'shell', 'am', 'start', '-n', $Activity,
    '--es', 'debugToken', $token,
    '--es', 'debugUsername', $Email,
    '--el', 'debugExpireSeconds', "$expireIn"
)
& $Adb @AdbArgs @startArgs | Out-String | Write-Host
if ($LASTEXITCODE -ne 0) { throw 'am start 失败（检查包是否已安装：.\build-client.ps1 install）' }

# ---------- 验证会话已落盘（debug 包支持 run-as；token 为密文不打印） ----------
Start-Sleep -Seconds 3
try {
    $xml = & $Adb @AdbArgs shell run-as $Package cat shared_prefs/float_account.xml 2>$null | Out-String
    $userLine = ($xml -split "`n" | Where-Object { $_ -match '<string name="username">' } | Select-Object -First 1)
    $expireLine = ($xml -split "`n" | Where-Object { $_ -match '<long name="expire_at"' } | Select-Object -First 1)
    if ($userLine) {
        $userShown = ($userLine -replace '.*<string name="username">', '' -replace '</string>.*', '')
        Write-Host "[dev-login] 已注入登录态：username=$userShown" -ForegroundColor Green
        if ($expireLine) { Write-Host "[dev-login] $($expireLine.Trim())" -ForegroundColor DarkGray }
    } else {
        Write-Host '[dev-login] 未读到 float_account（App 可能仍在启动，或当前包不是 debug/测试构建）' -ForegroundColor Yellow
    }
} catch {
    Write-Host '[dev-login] 跳过会话校验（run-as 不可用）' -ForegroundColor DarkGray
}

Write-Host '[dev-login] 完成：可继续 adb 自动化/截图验证' -ForegroundColor Green
