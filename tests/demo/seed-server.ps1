param([switch]$Reset)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$container = 'autojs6-dev-server'

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

$envMap = Read-DemoEnv
$argsLine = if ($Reset) { '-Reset' } else { '' }
$execArgs = @('docker', 'exec')
foreach ($key in @('DEMO_DEEPSEEK_API_KEY', 'LITELLM_BASE_URL', 'LITELLM_MASTER_KEY')) {
    if ($envMap.ContainsKey($key) -and $envMap[$key]) {
        $execArgs += @('-e', "$key=$($envMap[$key])")
    }
}
$execArgs += @($container, 'sh', '-lc', "cd /workspace/apps/server && node prisma/seed-demo.cjs $argsLine")

Write-Host "[seed-server] $container node prisma/seed-demo.cjs $argsLine" -ForegroundColor Cyan
& wsl @execArgs
$code = $LASTEXITCODE
if ($code -eq 2) {
    Write-Host '演示数据已存在（服务端），如需重灌请运行 .\reset.ps1 或 seed-server.ps1 -Reset'
    exit 2
}
if ($code -ne 0) {
    throw "seed-demo.cjs 失败（exit $code）"
}
