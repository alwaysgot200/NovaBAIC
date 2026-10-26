$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host '=== 1/3 重置并重新导入服务端演示数据 ===' -ForegroundColor Yellow
& (Join-Path $here 'seed-server.ps1') -Reset
if ($LASTEXITCODE -ne 0) { throw "服务端重置失败（exit $LASTEXITCODE）" }

Write-Host '=== 2/3 重置客户端演示数据 ===' -ForegroundColor Yellow
& (Join-Path $here 'seed-client.ps1') -Reset
if ($LASTEXITCODE -ne 0) { throw "客户端重置失败（exit $LASTEXITCODE）" }

Write-Host '=== 3/3 重新导入客户端演示数据 ===' -ForegroundColor Yellow
& (Join-Path $here 'seed-client.ps1')
if ($LASTEXITCODE -ne 0) { throw "客户端导入失败（exit $LASTEXITCODE）" }

Write-Host '重置并重新导入完成' -ForegroundColor Green
