param([switch]$Reset)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$manifest = Join-Path $here 'manifest.json'
$package = 'com.verlintas.baic2'
$receiver = "$package/.floatball.demo.DemoSeedReceiver"

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

function Resolve-Serial($envMap) {
    if ($envMap.ContainsKey('ADB_SERIAL') -and $envMap['ADB_SERIAL']) { return $envMap['ADB_SERIAL'] }
    $devices = & adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' }
    if (-not $devices) { throw '未发现已连接的 adb 设备' }
    $first = @($devices)[0]; return ($first -split '\s+')[0]
}

$envMap = Read-DemoEnv
$serial = Resolve-Serial $envMap

if (-not $Reset) {
    # 推送 manifest 到应用私有 files 目录（run-as，仅 debug 包可用）
    $json = [System.IO.File]::ReadAllText($manifest, [System.Text.Encoding]::UTF8)
    $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $json | & adb -s $serial shell "run-as $package sh -c 'cat > files/demo.json'"
    if ($LASTEXITCODE -ne 0) { throw '推送 manifest 失败（run-as 需要 debug 包）' }
    $amArgs = @('shell', 'am', 'broadcast', '-a', "$package.DEBUG_DEMO_SEED", '-n', $receiver, '--es', 'path', 'demo.json')
} else {
    $amArgs = @('shell', 'am', 'broadcast', '-a', "$package.DEBUG_DEMO_SEED", '-n', $receiver, '--ez', 'reset', 'true')
}

$output = (& adb -s $serial @amArgs 2>&1 | Out-String)
Write-Host $output.Trim()
if ($output -match 'result=2') {
    Write-Host '演示数据已存在（客户端），如需重灌请运行 .\reset.ps1 或 seed-client.ps1 -Reset'
    exit 2
}
if ($output -notmatch 'result=0') {
    throw '客户端演示数据导入失败（广播结果非 0）'
}
