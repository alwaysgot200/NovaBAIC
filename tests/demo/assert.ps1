param(
    [Parameter(Mandatory = $true)][string]$Origin,
    [Parameter(Mandatory = $true)][string]$Token,
    [Parameter(Mandatory = $true)][string]$CardKey,
    [string]$Serial = ''
)

$ErrorActionPreference = 'Stop'
$origin = $Origin.TrimEnd('/')

Write-Host "[assert] 1/4 网关配置接口（只回模型清单）" -ForegroundColor Cyan
$config = Invoke-RestMethod -Uri "$origin/api/v1/point-card/gateway/config" `
    -Headers @{ Authorization = "Bearer $Token" } -TimeoutSec 15
if ($config.code -ne 200) { throw "gateway/config 返回 code=$($config.code) msg=$($config.msg)" }
$keys = @($config.data.models | ForEach-Object { $_.key })
if (-not ($keys -contains 'deepseek-flash') -or -not ($keys -contains 'deepseek-pro')) {
    throw "模型清单缺少 deepseek-flash / deepseek-pro：$($keys -join ',')"
}
if ($keys -contains 'deepseek-hidden-demo') { throw '隐藏模型出现在了 App 清单中' }
$raw = $config | ConvertTo-Json -Depth 8
if ($raw -match 'apiKey|api_key|baseUrl.*https://api\.deepseek') {
    throw '网关配置泄露了上游密钥或上游地址'
}
Write-Host ("[assert] 模型=$($keys -join ',') default=$($config.data.defaultModel) baseUrl=$($config.data.baseUrl)")

Write-Host "[assert] 2/4 LiteLLM 网关直调（卡密=虚拟 key）" -ForegroundColor Cyan
foreach ($model in @('deepseek-flash', 'deepseek-pro')) {
    $body = @{
        model = $model
        messages = @(@{ role = 'user'; content = '只回复 OK' })
        max_tokens = 16
        temperature = 0
    } | ConvertTo-Json -Depth 5
    $response = Invoke-RestMethod -Method Post -Uri "$origin/llm/v1/chat/completions" `
        -Headers @{ Authorization = "Bearer $CardKey" } `
        -ContentType 'application/json' -Body $body -TimeoutSec 90
    if (-not $response.choices -or -not $response.choices[0].message) {
        throw "$model 调用未返回 choices"
    }
    Write-Host ("[assert] $model OK -> " + ($response.choices[0].message.content -replace '\s+', ' '))
}

Write-Host "[assert] 3/4 客户端安装/前台页面" -ForegroundColor Cyan
$serial = if ($Serial) { $Serial } else { (& adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' } | Select-Object -First 1) -split '\s+' | Select-Object -First 1 }
if (-not $serial) { throw '未发现 adb 设备' }
& adb -s $serial shell pm path com.verlintas.baic2 | Out-Null
if ($LASTEXITCODE -ne 0) { throw '客户端未安装' }
$foreground = & adb -s $serial shell dumpsys activity activities | Select-String 'mResumedActivity' | Out-String
if ($foreground -notmatch 'com.verlintas.baic2') {
    Write-Warning "App 当前不在前台：$($foreground.Trim())"
}

Write-Host "[assert] 4/4 演示数据清单" -ForegroundColor Cyan
$manifest = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'manifest.json'), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
Write-Host "  账号: $($manifest.server.account.username)"
Write-Host "  卡密: $($manifest.server.cards[0].cardKey) / $($manifest.server.cards[1].cardKey)"
Write-Host "  模型: $($manifest.server.models.key -join ', ')"
Write-Host '[assert] 全部通过' -ForegroundColor Green
