param(
    [switch]$Build,
    [switch]$Reset
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Read-DemoEnv {
    $map = @{}
    $file = Join-Path $here '.env.demo'
    if (Test-Path $file) {
        foreach ($line in Get-Content $file) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
                $map[$Matches[1]] = $Matches[2].Trim()
            }
        }
    }
    return $map
}

Write-Host '=== 1/5 网络模式（局域网优先，adb reverse 兜底）===' -ForegroundColor Yellow
$origin = (& (Join-Path $here 'net.ps1') -Mode Auto -Install:$Build | Select-Object -Last 1)
if (-not $origin) { throw 'net.ps1 未返回服务器地址' }
Write-Host "[run] 服务器=$origin"

Write-Host '=== 2/5 导入服务端演示数据 ===' -ForegroundColor Yellow
if ($Reset) { & (Join-Path $here 'seed-server.ps1') -Reset }
else { & (Join-Path $here 'seed-server.ps1') }
if ($LASTEXITCODE -eq 2) { exit 2 }

Write-Host '=== 3/5 导入客户端演示数据 ===' -ForegroundColor Yellow
if ($Reset) {
    & (Join-Path $here 'seed-client.ps1') -Reset
    if ($LASTEXITCODE -ne 0) { throw "客户端重置失败（exit $LASTEXITCODE）" }
}
& (Join-Path $here 'seed-client.ps1')
if ($LASTEXITCODE -eq 2) { exit 2 }

Write-Host '=== 4/5 账号登录（API 换 token，免手输）===' -ForegroundColor Yellow
$envMap = Read-DemoEnv
$manifest = [System.IO.File]::ReadAllText((Join-Path $here 'manifest.json'), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$username = $manifest.server.account.username
$password = $manifest.server.account.password
$cardKey = $manifest.server.cards[0].cardKey
$defaultModel = $manifest.server.defaultModel

$codeResp = Invoke-RestMethod -Uri "$origin/api/v1/auth/code" -TimeoutSec 15
$uuid = $codeResp.data.uuid
$captchaEnabled = [string]$codeResp.data.captchaEnabled -eq 'true'
$loginBody = @{
    username = $username
    password = $password
    clientId = 'haocanmou'
    grantType = 'password'
}
if ($captchaEnabled) {
    # --no-auth-warning：redis-cli 的 -a 警告会写 stderr，在 Stop 模式下会被当成
    # NativeCommandError 终止脚本（2026-10-26 全链路实测命中），故显式关闭该警告。
    $answer = (& wsl docker exec nest-admin-redis redis-cli --no-auth-warning -a 123456 -n 2 GET "captcha_codes:$uuid" | Out-String).Trim()
    $answer = ($answer -split "`n" | Where-Object { $_.Trim() } | Select-Object -Last 1).Trim()
    if (-not $answer) { throw '未能从 Redis 读取验证码答案' }
    $loginBody.code = $answer
    $loginBody.uuid = $uuid
}
$login = Invoke-RestMethod -Method Post -Uri "$origin/api/v1/auth/login" `
    -ContentType 'application/json' -Body ($loginBody | ConvertTo-Json) -TimeoutSec 20
if ($login.code -ne 200) { throw "登录失败：code=$($login.code) msg=$($login.msg)" }
$token = $login.data.access_token
$refresh = $login.data.refresh_token
Write-Host "[run] 登录成功：$username"

$serial = if ($envMap.ContainsKey('ADB_SERIAL') -and $envMap['ADB_SERIAL']) { $envMap['ADB_SERIAL'] } else { '' }
$adbTarget = if ($serial) { @('-s', $serial) } else { @() }
& adb @adbTarget shell am force-stop com.verlintas.baic2
& adb @adbTarget shell am start -n com.verlintas.baic2/.MainActivity `
    --es debugToken $token --es debugRefreshToken $refresh `
    --es debugUsername $username --es debugCardKey $cardKey --es debugModel $defaultModel
if ($LASTEXITCODE -ne 0) { throw '启动 App 失败' }

Write-Host '=== 5/5 断言（网关/模型/LLM/前台）===' -ForegroundColor Yellow
& (Join-Path $here 'assert.ps1') -Origin $origin -Token $token -CardKey $cardKey -Serial $serial
if ($LASTEXITCODE -ne 0) { throw '断言失败' }

Write-Host 'run.ps1 完成：数据已导入、App 已免登录启动、模型与网关可用' -ForegroundColor Green
