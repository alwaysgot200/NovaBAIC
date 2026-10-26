param(
    [ValidateSet('Auto', 'Lan', 'Reverse')]
    [string]$Mode = 'Auto',
    [switch]$Install,
    [string]$ApkPath
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = (Resolve-Path (Join-Path $here '..\..')).Path
$generated = Join-Path $here '.origin.generated'

function Read-DemoEnv {
    $file = Join-Path $here '.env.demo'
    $map = @{}
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
    if (-not $devices) { throw '未发现已连接的 adb 设备：请先开启 USB 调试并授权' }
    $first = @($devices)[0]; return ($first -split '\s+')[0]
}

function Get-LanIp {
    $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
        Sort-Object RouteMetric | Select-Object -First 1
    if ($route) {
        $candidate = Get-NetIPAddress -InterfaceIndex $route.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
            Select-Object -First 1
        if ($candidate) { return $candidate.IPAddress }
    }
    $fallback = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and
            $_.InterfaceAlias -notmatch 'vEthernet|WSL|Docker|Loopback'
        } | Select-Object -First 1
    if ($fallback) { return $fallback.IPAddress }
    throw '未探测到局域网 IPv4 地址'
}

function Test-Health([string]$origin) {
    try {
        $response = Invoke-WebRequest -Uri "$origin/api/v1/health/ready" -UseBasicParsing -TimeoutSec 6
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 300
    } catch {
        return $false
    }
}

$envMap = Read-DemoEnv
$serial = Resolve-Serial $envMap
$ip = $null
$mode = $Mode
$origin = $null

if ($envMap.ContainsKey('NOVA_TEST_ORIGIN') -and $envMap['NOVA_TEST_ORIGIN']) {
    $origin = $envMap['NOVA_TEST_ORIGIN'].TrimEnd('/')
    $mode = 'Lan'
} else {
    try { $ip = Get-LanIp } catch { $ip = $null }
    $lanOrigin = if ($ip) { "http://${ip}:8080" } else { $null }
    if ($mode -eq 'Auto') {
        $mode = if ($lanOrigin -and (Test-Health $lanOrigin)) { 'Lan' } else { 'Reverse' }
    }
    if ($mode -eq 'Lan') {
        if (-not $lanOrigin) { throw '未能探测局域网 IP，请改用 -Mode Reverse' }
        $origin = $lanOrigin
        if (-not (Test-Health $origin)) {
            Write-Warning "局域网健康检查未通过：$origin（请确认 dev 容器已启动、Windows 防火墙放行 8080）"
        }
    } else {
        $origin = 'http://127.0.0.1:8080'
    }
}

# reverse 恒作为兜底（LAN 模式下保留也不冲突）
& adb -s $serial reverse tcp:8080 tcp:8080 | Out-Null
Write-Host "[net] 设备=$serial 模式=$mode 端点=$origin"

@{ mode = $mode; origin = $origin; ip = $ip; serial = $serial; generatedAt = (Get-Date).ToString('s') } |
    ConvertTo-Json | Set-Content -Path $generated -Encoding UTF8

if ($Install) {
    $gradlew = Join-Path $root 'gradlew.bat'
    Write-Host "[net] 构建 debug 包（testOrigin=$origin）..."
    & $gradlew ':app:assembleDebug' "-Pbaic.testOrigin=$origin" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败（exit $LASTEXITCODE）" }
    $apk = if ($ApkPath) { $ApkPath } else { Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk' }
    if (-not (Test-Path $apk)) { throw "未找到 APK：$apk" }
    & adb -s $serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "APK 安装失败（exit $LASTEXITCODE）" }
}

if (-not (Test-Health $origin)) {
    throw "健康检查失败：$origin/api/v1/health/ready。请先启动 dev 容器（build-server.ps1 dev）并确认防火墙放行 8080"
}

Write-Output $origin
