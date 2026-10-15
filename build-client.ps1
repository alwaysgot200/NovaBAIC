<#
.SYNOPSIS
    NovaBAIC (BetterAIChat2) Android 客户端 构建 / 部署 / 日志工具 (Windows PowerShell 5.1+)

.DESCRIPTION
    脚本只负责: 编译 -> 安装到设备 -> 启动 -> 看日志 (等价于 Android Studio 的 Run)。
    构建类命令 (dev / build / test / release 等) 先过 Kotlin 编译门禁
    (:app:compileDebugKotlin), 语法/类型不合格立即终止, 不白启模拟器。

    开发环境 API Key: local.properties 的 dev.deepseekApiKey (已被 gitignore) 会在
    debug 构建注入 BuildConfig, App 首启由 DevAgentSeeder 自动预置 DeepSeek Agent,
    无需每次在模拟器里手动配置。release 构建恒为空串, 生产环境不受影响。

    Android 模拟器 / 雷电模拟器 (多开实例) / USB 真机统一支持:
    非交互命令 (dev 等) 已连设备直接用, 否则自动启动第一个 AVD/雷电实例;
    交互模式会先列出全部部署目标, 启动任何模拟器前都会确认。

.EXAMPLE
    .\build-client.ps1                  # 交互菜单 (直接回车 = 编译安装并启动)
    .\build-client.ps1 dev              # 非交互: 增量安装 + 启动 + 全量日志
    .\build-client.ps1 dev -NoLog       # 安装启动, 不附着日志
    .\build-client.ps1 log              # 仅看 App 日志
    .\build-client.ps1 build            # 仅构建 Debug APK
    .\build-client.ps1 test             # 单元测试
    .\build-client.ps1 help             # 全部命令
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet(
        'menu', 'help',
        'dev', 'deploy', 'quick',
        'log',
        'build', 'test',
        'reverse',
        'stop', 'clear-data', 'grant',
        'smoke', 'record', 'record-stop',
        'start-emu', 'kill-emu',
        'clean', 'rebuild', 'release', 'install-release',
        'apk'
    )]
    [string] $Command = 'menu',

    # 部署后不附着 logcat
    [switch] $NoLog
)

# ======================== 项目配置 ========================

# Gradle 工程根目录 (本脚本就在工程根目录)
$ProjectDir = $PSScriptRoot

# 客户端包名 (无 applicationIdSuffix, debug/release 同包名)
$AppId = 'com.verlintas.baic2'

# 本机代理 (Gradle 走 Tencent 镜像时不需要; 只有拉远程依赖超时才打开)
$UseProxy = $false
$ProxyHost = '127.0.0.1'
$ProxyPort = 10808

# Gradle 附加参数
$GradleExtraArgs = @('--console=plain')

# 模拟器冷启动超时 (秒), 首次开机可能较慢
$BootTimeoutSec = 300

# 保证 adb 输出 (exec-out 文本) 在 PS 5.1 下按 UTF-8 解码
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# ======================== SDK / 设备解析 ========================

# 依次从 local.properties、环境变量、默认路径解析 Android SDK
function Get-SdkRoot {
    $localProps = Join-Path $ProjectDir 'local.properties'
    if (Test-Path $localProps) {
        $line = (Get-Content $localProps -ErrorAction SilentlyContinue | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1)
        if ($line) {
            $val = ($line -split '=', 2)[1].Trim()
            # Java properties 转义还原: \: -> :  \\ -> \
            $val = $val -replace '\\:', ':' -replace '\\\\', '\'
            if ($val -and (Test-Path $val)) { return $val }
        }
    }
    foreach ($envName in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
        $v = [Environment]::GetEnvironmentVariable($envName)
        if ($v -and (Test-Path $v)) { return $v }
    }
    $fallback = 'D:\programfiles\Android\SDK'
    if (Test-Path $fallback) { return $fallback }
    return $null
}

$SdkRoot = Get-SdkRoot
$Adb = if ($SdkRoot) { Join-Path $SdkRoot 'platform-tools\adb.exe' } else { 'adb' }
$Emulator = if ($SdkRoot) { Join-Path $SdkRoot 'emulator\emulator.exe' } else { 'emulator' }

# 预热 adb server: 首次执行任意 adb 命令时, adb 原生会连吐两行
# ("daemon not running; starting now ..." + "daemon started successfully")。
# 这里在脚本最前面静默拉起, 仅在本次确实新启动了 server 时打印一行干净的确认;
# server 已在运行则完全静默。
function Initialize-AdbServer {
    $out = (& $Adb start-server 2>&1 | Out-String)
    if ($out -match 'starting now') {
        Write-Host '* daemon started successfully at tcp:5037' -ForegroundColor DarkGray
    }
}

Initialize-AdbServer

function Get-AvdList {
    if (-not (Test-Path $Emulator)) { return }
    $list = & $Emulator -list-avds 2>$null
    if (-not $list) { return }
    # 直接让数组元素逐个流出函数 (不要用一元逗号包裹, 否则调用方拿到嵌套数组);
    # 需要数组语义的调用方用 @(Get-AvdList) 包裹。
    @($list | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
}

# ======================== Release 签名预检 ========================

# 检查 release 签名凭据是否就绪, 与 app/build.gradle.kts 的 signingConfigs 逻辑对齐:
#   1) 根目录 keystore.properties 的 storeFile (相对路径按工程根解析, 绝对路径原样);
#   2) 环境变量 BAIC2_KEYSTORE_PATH。
# 返回 $true=就绪; $false=缺失或文件不存在 (产物将是未签名 APK)。
function Test-ReleaseSigning {
    $propsPath = Join-Path $ProjectDir 'keystore.properties'
    if (Test-Path $propsPath) {
        $line = (Get-Content $propsPath -ErrorAction SilentlyContinue | Where-Object { $_ -match '^\s*storeFile\s*=' } | Select-Object -First 1)
        if ($line) {
            $val = ($line -split '=', 2)[1].Trim()
            $store = if ($val -and [System.IO.Path]::IsPathRooted($val)) { $val } else { Join-Path $ProjectDir $val }
            if ($val -and (Test-Path $store)) { return $true }
            Write-Host "keystore.properties 存在, 但 storeFile 指向的文件不存在: $val" -ForegroundColor Yellow
            return $false
        }
    }
    $envPath = [Environment]::GetEnvironmentVariable('BAIC2_KEYSTORE_PATH')
    if ($envPath -and (Test-Path $envPath)) { return $true }
    return $false
}

# 签名缺失时打印配置指引, 返回是否就绪。
function Show-ReleaseSigningWarning {
    if (Test-ReleaseSigning) { return $true }
    Write-Host '警告: 未检测到 release 签名凭据, 本次产物将是未签名 APK (设备无法安装、无法发布)。' -ForegroundColor Yellow
    Write-Host '配置方法 (二选一, 凭据文件均已被 gitignore):' -ForegroundColor DarkGray
    Write-Host '  A) 工程根目录 keystore.properties:' -ForegroundColor DarkGray
    Write-Host '       storeFile=release.keystore' -ForegroundColor DarkGray
    Write-Host '       storePassword=***' -ForegroundColor DarkGray
    Write-Host '       keyAlias=***' -ForegroundColor DarkGray
    Write-Host '       keyPassword=***' -ForegroundColor DarkGray
    Write-Host '  B) 环境变量 BAIC2_KEYSTORE_PATH / BAIC2_KEYSTORE_PASSWORD / BAIC2_KEY_ALIAS / BAIC2_KEY_PASSWORD' -ForegroundColor DarkGray
    return $false
}

# ======================== 雷电模拟器 (LDPlayer) ========================

# 雷电模拟器控制台为 dnconsole.exe。安装路径优先从注册表卸载信息解析,
# 找不到再扫常见目录。结果缓存, 避免每次刷新菜单都重复扫描。
$script:LdDirCache = $null
$script:LdDirResolved = $false
function Get-LdPlayerDir {
    if ($script:LdDirResolved) { return $script:LdDirCache }
    $script:LdDirResolved = $true

    # 1) 注册表卸载信息 (DisplayName 含 雷电/LDPlayer)
    $regRoots = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
    )
    foreach ($root in $regRoots) {
        $items = Get-ItemProperty $root -ErrorAction SilentlyContinue |
            Where-Object { $_.DisplayName -match '雷电|LDPlayer' }
        foreach ($item in $items) {
            $candidates = @($item.InstallLocation)
            if ($item.DisplayIcon) {
                # DisplayIcon 形如 "C:\...\dnplayer.exe,0", 去掉末尾参数再取目录
                $iconPath = $item.DisplayIcon -replace ',\d+\s*$', ''
                $candidates += Split-Path $iconPath -Parent
            }
            foreach ($cand in $candidates) {
                if ($cand -and ((Test-Path (Join-Path $cand 'ldconsole.exe')) -or (Test-Path (Join-Path $cand 'dnconsole.exe')))) {
                    $script:LdDirCache = $cand.TrimEnd('\')
                    return $script:LdDirCache
                }
            }
        }
    }

    # 2) 常见安装目录 (扫描各盘符)
    foreach ($drive in (Get-PSDrive -PSProvider FileSystem).Root) {
        foreach ($sub in @('LDPlayer\LDPlayer9', 'LDPlayer\LDPlayer4', 'leidian\LDPlayer9', 'leidian', 'ChangZhi\dnplayer2')) {
            $cand = Join-Path $drive $sub
            if ((Test-Path (Join-Path $cand 'ldconsole.exe')) -or (Test-Path (Join-Path $cand 'dnconsole.exe'))) {
                $script:LdDirCache = $cand.TrimEnd('\')
                return $script:LdDirCache
            }
        }
    }

    return $null
}

# 返回雷电控制台程序路径: 优先 ldconsole.exe (无需管理员权限), 退回旧版 dnconsole.exe
function Get-LdConsole {
    param([Parameter(Mandatory = $true)][string] $LdDir)
    $ld = Join-Path $LdDir 'ldconsole.exe'
    if (Test-Path $ld) { return $ld }
    $dn = Join-Path $LdDir 'dnconsole.exe'
    if (Test-Path $dn) { return $dn }
    return $null
}

# 列出雷电多开实例。控制台 list2 每行 (字段数随版本不同):
#   索引,标题,顶层窗口句柄,绑定窗口句柄,运行状态(1=运行中 0=已停止),PID,...
function Get-LdPlayerInstances {
    param([Parameter(Mandatory = $true)][string] $LdDir)

    $console = Get-LdConsole -LdDir $LdDir
    if (-not $console) { return }

    # 雷电控制台按系统 OEM 代码页输出 (中文 Windows=GBK/936),
    # 脚本全局把 [Console]::OutputEncoding 设为 UTF-8, 直接捕获会导致标题乱码, 临时切回 OEM 代码页。
    $prevEncoding = [Console]::OutputEncoding
    try {
        $oem = [System.Text.Encoding]::GetEncoding((Get-Culture).TextInfo.OEMCodePage)
        [Console]::OutputEncoding = $oem
        $rows = @(& $console list2 2>$null)
    }
    finally {
        [Console]::OutputEncoding = $prevEncoding
    }
    # 元素逐个流出, 调用方需要数组时用 @(...) 包裹 (避免一元逗号导致嵌套数组)
    @(foreach ($row in $rows) {
        $f = ($row -split ',')
        if ($f.Count -ge 5 -and ($f[0].Trim() -match '^\d+$')) {
            [pscustomobject]@{
                Index   = [int] $f[0].Trim()
                Title   = $f[1].Trim()
                Running = ($f[4].Trim() -eq '1')
            }
        }
    })
}

# 雷电实例 i 的固定端口: 控制台 5554+i*2, adb 5555+i*2
function Get-LdPorts {
    param([Parameter(Mandatory = $true)][int] $Index)
    return [pscustomobject]@{
        Console = 5554 + $Index * 2
        Adb     = 5555 + $Index * 2
    }
}

# ======================== 基础函数 ========================

# 启用 Windows conhost 的 ANSI/VT 转义处理 (老版本 conhost 默认关闭,
# 不关的话 ESC 序列会被原样打印成乱码; Windows Terminal / PSReadLine 环境通常已开)。
# 非控制台主机或受限环境调用失败时静默忽略, 后续退化为 Clear-Host。
function Enable-VirtualTerminal {
    if ($Host.Name -ne 'ConsoleHost') { return }
    try {
        if (-not ('Win32.ConsoleNative' -as [type])) {
            Add-Type -Namespace Win32 -Name ConsoleNative -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("kernel32.dll")]
public static extern System.IntPtr GetStdHandle(int nStdHandle);
[System.Runtime.InteropServices.DllImport("kernel32.dll")]
public static extern bool GetConsoleMode(System.IntPtr hConsoleHandle, out uint lpMode);
[System.Runtime.InteropServices.DllImport("kernel32.dll")]
public static extern bool SetConsoleMode(System.IntPtr hConsoleHandle, uint dwMode);
'@
        }
        $handle = [Win32.ConsoleNative]::GetStdHandle(-11)
        $mode = [uint32] 0
        if ([Win32.ConsoleNative]::GetConsoleMode($handle, [ref]$mode)) {
            # 0x0004 = ENABLE_VIRTUAL_TERMINAL_PROCESSING
            [Win32.ConsoleNative]::SetConsoleMode($handle, $mode -bor 0x0004) | Out-Null
        }
    }
    catch { }
}

Enable-VirtualTerminal

# 清屏: 系统自带 Clear-Host 在长行折行时会留残字, 且不清滚动回溯
# (旧画面往上滚到历史里, 全选复制时新旧画面叠在一起)。
# 改用 ANSI: ESC[2J 清可见区, ESC[3J 清滚动回溯, ESC[H 光标回原点。
# 不识别 ANSI 的主机退化为 Clear-Host。
function Clear-Screen {
    try {
        $esc = [char] 27
        Write-Host "$esc[2J$esc[3J$esc[H" -NoNewline
    }
    catch {
        Clear-Host
    }
}

function Write-Header {
    Clear-Screen
    Write-Host '========================================='
    Write-Host '   NovaBAIC 构建 / 部署 / 联调工具 (PS1)'
    Write-Host '========================================='
    $online = Get-ConnectedDevice
    if ($online) {
        $kind = if ($online -match '^emulator-') { '模拟器' } else { '实体设备' }
        Write-Host (" 当前设备: {0} ({1})" -f $online, $kind) -ForegroundColor Green
    }
    else {
        $avdCount = @(Get-AvdList).Count
        $ldNote = if (Get-LdPlayerDir) { ' | 雷电模拟器: 已检测' } else { '' }
        Write-Host " 当前设备: 无 (可用 AVD: $avdCount 个$ldNote)" -ForegroundColor Yellow
    }
}

function Pause-ForMenu {
    Read-Host '按回车返回菜单' | Out-Null
}

# 配置 Gradle 所需的 JVM 代理 (Gradle/Groovy 的 HttpURLConnection 只认 JVM 参数, 不认 http_proxy 环境变量)
function Set-ProxyEnv {
    if ($UseProxy) {
        $env:JAVA_TOOL_OPTIONS = "-Dhttp.proxyHost=$ProxyHost -Dhttp.proxyPort=$ProxyPort -Dhttps.proxyHost=$ProxyHost -Dhttps.proxyPort=$ProxyPort"
    }
}

# 在工程目录中执行 gradlew.bat, 失败返回 $false
function Invoke-Gradle {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]] $Arguments)

    if (-not (Test-Path (Join-Path $ProjectDir 'gradlew.bat'))) {
        Write-Host "未找到 gradlew.bat, 请确认工程目录: $ProjectDir" -ForegroundColor Red
        return $false
    }

    Set-ProxyEnv
    Push-Location $ProjectDir
    try {
        & .\gradlew.bat @Arguments @GradleExtraArgs
        return ($LASTEXITCODE -eq 0)
    }
    finally {
        Pop-Location
    }
}

# 编译前 Kotlin 语法门禁: 只跑编译任务 (不做 dex/打包/安装), 快速拦截语法/类型错误。
# 增量编译下, 之前编译过则近乎零成本 (UP-TO-DATE)。
function Invoke-KotlinCheck {
    Write-Host '==> 前置语法检查: :app:compileDebugKotlin ...' -ForegroundColor Cyan
    if (-not (Invoke-Gradle ':app:compileDebugKotlin')) {
        Write-Host '语法检查未通过 (compileDebugKotlin), 已阻止本次构建/部署。请修复上方 Kotlin 报错。' -ForegroundColor Red
        return $false
    }
    Write-Host '语法检查通过 (compileDebugKotlin)' -ForegroundColor Green
    return $true
}

function Get-AdbDeviceSerials {
    # adb devices 行格式: "序列号`tdevice"; offline/unauthorized 不计入。
    # 元素逐个流出; 调用方用 @(Get-AdbDeviceSerials) 得到数组 (禁止一元逗号, 会产生嵌套数组)。
    @((& $Adb devices) -match "`tdevice`$" | ForEach-Object { ($_ -split "`t")[0] })
}

function Get-ConnectedDevice {
    # 优先返回实体设备 (USB/局域网真机), 否则取第一个模拟器
    $devices = Get-AdbDeviceSerials
    if (-not $devices) { return $null }
    $real = $devices | Where-Object { $_ -notmatch '^emulator-|^127\.0\.0\.1:' } | Select-Object -First 1
    if ($real) { return $real }
    return ($devices | Select-Object -First 1)
}

# 后台启动指定 AVD, 并等待系统开机完成 (sys.boot_completed=1), 返回设备序列号
function Start-Avd {
    param([Parameter(Mandatory = $true)][string] $AvdName)

    if (-not (Test-Path $Emulator)) {
        Write-Host "未找到 emulator: $Emulator" -ForegroundColor Red
        return $null
    }

    # 启动前清理上次异常退出残留的锁 (snapshot.lock.lock error:5 的常见原因)
    # 仅在当前没有任何模拟器实例时执行, 避免误删正在运行实例的锁
    $runningEmus = (& $Adb devices) -match '^emulator-\d+'
    if (-not $runningEmus) {
        $avdIni = Join-Path $env:USERPROFILE ".android\avd\$AvdName.ini"
        $avdPath = $null
        if (Test-Path $avdIni) {
            $p = ((Get-Content $avdIni -ErrorAction SilentlyContinue) -match '^path=' | Select-Object -First 1)
            if ($p) { $avdPath = ($p -split '=', 2)[1].Trim() }
        }
        if ($avdPath -and (Test-Path $avdPath)) {
            foreach ($lockName in @('multiinstance.lock', 'snapshot.lock.lock')) {
                $lf = Join-Path $avdPath $lockName
                if (Test-Path $lf -PathType Leaf) {
                    Remove-Item $lf -Force -Confirm:$false -ErrorAction SilentlyContinue
                    Write-Host "已清理残留锁文件: $lockName" -ForegroundColor DarkGray
                }
            }
            $ld = Join-Path $avdPath 'hardware-qemu.ini.lock'
            if (Test-Path $ld -PathType Container) {
                Remove-Item $ld -Recurse -Force -Confirm:$false -ErrorAction SilentlyContinue
                Write-Host '已清理残留锁目录: hardware-qemu.ini.lock' -ForegroundColor DarkGray
            }
        }
    }

    Write-Host "正在后台启动模拟器: $AvdName ..." -ForegroundColor Cyan
    # -accel auto: WHPX (与 WSL2/Docker 共存) 优先; AEHD 已禁用
    Start-Process -FilePath $Emulator -ArgumentList @('-avd', $AvdName, '-accel', 'auto', '-gpu', 'auto') | Out-Null

    $deadline = (Get-Date).AddSeconds($BootTimeoutSec)

    # 1) 等待 emulator 设备出现在 adb 列表
    $device = $null
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $device = ((& $Adb devices) -match '^emulator-\d+' | ForEach-Object { ($_ -split "`t")[0] } | Select-Object -First 1)
        if ($device) { break }
    }
    if (-not $device) {
        Write-Host "等待模拟器上线超时 (${BootTimeoutSec}s)。" -ForegroundColor Red
        return $null
    }
    Write-Host "设备已上线: $device, 等待系统启动完成 ..." -ForegroundColor Cyan

    & $Adb -s $device wait-for-device | Out-Null

    # 2) 等待 boot_completed
    while ((Get-Date) -lt $deadline) {
        # adb 输出为空/多行数组时直接 .Trim() 会报错, 先 -join '' 归一为字符串
        $booted = ((& $Adb -s $device shell getprop sys.boot_completed 2>$null) -join '').Trim()
        if ($booted -eq '1') {
            Write-Host "模拟器启动完成: $device" -ForegroundColor Green
            return $device
        }
        Start-Sleep -Seconds 2
    }

    Write-Host "等待系统开机完成超时 (${BootTimeoutSec}s)。" -ForegroundColor Red
    return $null
}

# 启动 (或连接已在运行的) 雷电模拟器实例, 等待 boot_completed, 返回 adb 设备序列号。
# 雷电实例通常被 SDK adb 自动发现为 emulator-5554; 未自动发现时用 adb connect 127.0.0.1:<port>。
function Start-LdPlayerInstance {
    param(
        [Parameter(Mandatory = $true)][int] $Index,
        [Parameter(Mandatory = $true)][string] $LdDir
    )

    $ports = Get-LdPorts -Index $Index
    $serialEmu = "emulator-$($ports.Console)"
    $serialNet = "127.0.0.1:$($ports.Adb)"

    $inst = Get-LdPlayerInstances -LdDir $LdDir | Where-Object { $_.Index -eq $Index } | Select-Object -First 1
    if (-not $inst -or -not $inst.Running) {
        Write-Host "正在启动雷电模拟器 (实例 $Index) ..." -ForegroundColor Cyan
        & (Get-LdConsole -LdDir $LdDir) launch --index $Index | Out-Null
    }
    else {
        Write-Host "雷电实例 $Index 已在运行, 尝试通过 adb 连接 ..." -ForegroundColor Cyan
    }

    $deadline = (Get-Date).AddSeconds($BootTimeoutSec)

    # 1) 等待设备上线 (自动发现 或 adb connect)
    $device = $null
    while ((Get-Date) -lt $deadline) {
        $device = Get-AdbDeviceSerials |
            Where-Object { $_ -eq $serialEmu -or $_ -eq $serialNet } |
            Select-Object -First 1
        if (-not $device) {
            & $Adb connect $serialNet 2>$null | Out-Null
            Start-Sleep -Seconds 2
            $device = Get-AdbDeviceSerials |
                Where-Object { $_ -eq $serialEmu -or $_ -eq $serialNet } |
                Select-Object -First 1
        }
        if ($device) { break }
        Start-Sleep -Seconds 2
    }
    if (-not $device) {
        Write-Host "等待雷电模拟器上线超时 (${BootTimeoutSec}s)。" -ForegroundColor Red
        return $null
    }
    Write-Host "设备已上线: $device, 等待系统启动完成 ..." -ForegroundColor Cyan

    & $Adb -s $device wait-for-device | Out-Null

    # 2) 等待 boot_completed
    while ((Get-Date) -lt $deadline) {
        $booted = ((& $Adb -s $device shell getprop sys.boot_completed 2>$null) -join '').Trim()
        if ($booted -eq '1') {
            Write-Host "雷电模拟器启动完成: $device" -ForegroundColor Green
            return $device
        }
        Start-Sleep -Seconds 2
    }

    Write-Host "等待雷电模拟器开机完成超时 (${BootTimeoutSec}s)。" -ForegroundColor Red
    return $null
}

# 查询运行中模拟器对应的 AVD 名 (用于列表去重); 失败或不支持返回 $null
function Get-RunningAvdName {
    param([Parameter(Mandatory = $true)][string] $Device)
    $name = ((& $Adb -s $Device emu avd name 2>$null | Select-Object -First 1) -join '').Trim()
    if ($name -ne '') { return $name }
    return $null
}

# 交互式统一部署目标选择:
#   已连接的 adb 设备 (USB 真机 / 网络设备 / 运行中的模拟器)
#   雷电模拟器多开实例 (未运行则先拉起并等待开机)
#   Android AVD (未运行则先拉起并等待开机)
# 返回选中/启动后的设备序列号; 取消或无可用目标返回 $null。
function Select-Device {
    $items = @()
    $serials = @(Get-AdbDeviceSerials)

    # -- 1) 已连接设备, 直接可用 (真机优先, 其次网络连接的模拟器, 最后 emulator-*) --
    $orderedSerials = $serials | Sort-Object {
        if ($_ -notmatch '^emulator-|^127\.0\.0\.1:') { 0 }
        elseif ($_ -match '^127\.0\.0\.1:') { 1 }
        else { 2 }
    }
    foreach ($s in $orderedSerials) {
        if ($s -match '^emulator-') { $kind = '模拟器' }
        elseif ($s -match '^127\.0\.0\.1:') { $kind = '模拟器(adb connect)' }
        else { $kind = '真机' }
        $model = ((& $Adb -s $s shell getprop ro.product.model 2>$null) -join '').Trim()
        $label = if ($model) { "{0} {1} [{2}]" -f $kind, $model, $s } else { "{0} [{1}]" -f $kind, $s }
        $items += [pscustomobject]@{ Kind = 'adb'; Label = $label; Device = $s }
    }

    # 雷电控制台输出多开实例; 端口仅在启动等待时使用, 不用做去重
    # (AVD 与雷电实例都可能占用 emulator-5554, 按端口无法区分身份)
    $ldDir = Get-LdPlayerDir
    if ($ldDir) {
        foreach ($ld in Get-LdPlayerInstances -LdDir $ldDir) {
            # -- 2) 雷电实例: 无论是否已在 adb 列表都列出 (多开实例的可靠入口) --
            $state = if ($ld.Running) { '运行中, adb 连接后部署' } else { '未运行, 启动后部署' }
            $items += [pscustomobject]@{
                Kind    = 'ld'
                Label   = ("雷电模拟器 {0} [实例{1}] ({2})" -f $ld.Title, $ld.Index, $state)
                LdIndex = $ld.Index
                LdDir   = $ldDir
            }
        }
    }

    # -- 3) Android AVD (跳过已在运行的; 雷电不识别 emu avd name 时返回空, 无害) --
    $runningAvds = @()
    foreach ($s in ($serials | Where-Object { $_ -match '^emulator-' })) {
        $n = Get-RunningAvdName -Device $s
        if ($n) { $runningAvds += $n }
    }
    foreach ($a in Get-AvdList) {
        if ($runningAvds -contains $a) { continue }
        $items += [pscustomobject]@{ Kind = 'avd'; Label = "Android AVD $a (启动后部署)"; AvdName = $a }
    }

    if ($items.Count -eq 0) {
        Write-Host '未找到任何可用目标: 请用 USB 连接手机并开启 USB 调试、启动/安装雷电模拟器, 或先创建 AVD。' -ForegroundColor Yellow
        return $null
    }

    Write-Host ''
    Write-Host '请选择部署目标:' -ForegroundColor Cyan
    for ($i = 0; $i -lt $items.Count; $i++) {
        $tail = ''
        if ($i -eq 0 -and $items[0].Kind -eq 'adb') { $tail = '  [回车默认]' }
        Write-Host (" {0}) {1}{2}" -f ($i + 1), $items[$i].Label, $tail)
    }
    Write-Host ' 0) 取消'
    $sel = Read-Host '选择目标'
    if ($sel -eq '') {
        if ($items[0].Kind -eq 'adb') { $sel = '1' } else { return $null }
    }
    if ($sel -eq '0' -or $sel -eq '') { return $null }

    $index = 0
    if (-not [int]::TryParse($sel, [ref]$index) -or $index -lt 1 -or $index -gt $items.Count) {
        Write-Host "无效选择: $sel" -ForegroundColor Yellow
        return $null
    }

    $item = $items[$index - 1]
    switch ($item.Kind) {
        'adb' { return $item.Device }
        'ld' { return Start-LdPlayerInstance -Index $item.LdIndex -LdDir $item.LdDir }
        'avd' { return Start-Avd -AvdName $item.AvdName }
    }
    return $null
}

# 确保有可用设备并返回序列号:
#   交互模式 (默认)   : 弹统一目标选择 (真机 / 雷电 / AVD), 启动任何模拟器前一律手动确认;
#   非交互 -AutoStartAvd (如 dev 命令): 已连设备直接用, 否则自动启动第一个 AVD, 再退而求其次启动雷电实例。
function Ensure-Device {
    param([switch] $AutoStartAvd)

    if (-not $AutoStartAvd) { return Select-Device }

    $device = Get-ConnectedDevice
    if ($device) { return $device }

    $avds = @(Get-AvdList)
    if ($avds) {
        Write-Host "未检测到设备, 自动启动 AVD: $($avds[0])" -ForegroundColor Yellow
        return Start-Avd -AvdName $avds[0]
    }

    $ldDir = Get-LdPlayerDir
    if ($ldDir) {
        $lds = @(Get-LdPlayerInstances -LdDir $ldDir)
        if ($lds.Count -gt 0) {
            $first = $lds | Sort-Object Index | Select-Object -First 1
            Write-Host "未检测到设备, 自动启动雷电模拟器实例 $($first.Index) ($($first.Title)) ..." -ForegroundColor Yellow
            return Start-LdPlayerInstance -Index $first.Index -LdDir $ldDir
        }
    }

    Write-Host '没有已连接的设备, 也没有找到 AVD 或雷电模拟器。请用 USB 连接手机 (开启 USB 调试)。' -ForegroundColor Yellow
    return $null
}

# ======================== 联调支持 ========================

# 建立 设备 localhost:8080 -> 宿主机 8080 的反向转发。
# 用途: App 需要访问宿主机本机服务时 (真机没有 10.0.2.2 别名, 且本机端口通常只绑环回)。
# 模拟器访问宿主机直接用 10.0.2.2:<port>。
# 注意: 映射保存在 adb server 侧, adb kill-server / 重启后需重新执行。
function Enable-ServerReverse {
    param(
        [Parameter(Mandatory = $true)][string] $Device,
        [int] $Port = 8080
    )
    & $Adb -s $Device reverse "tcp:$Port" "tcp:$Port" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'adb reverse 失败, 请检查设备连接与 USB 调试授权。' -ForegroundColor Yellow
        return $false
    }
    Write-Host "已建立 adb reverse: 设备 localhost:$Port -> 宿主机 :$Port" -ForegroundColor Green
    return $true
}

# 增量编译 + 安装, 并用 pm list packages 做结果校验。
# 冷开机模拟器刚 boot_completed 时 PMS 可能尚未就绪, Gradle 偶发安装失败, 故带重试。
function Install-DebugApp {
    param(
        [Parameter(Mandatory = $true)][string] $Device,
        [int] $MaxAttempts = 3
    )
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        Write-Host "增量编译并安装 Debug (第 $attempt/$MaxAttempts 次) ..." -ForegroundColor Cyan
        if (Invoke-Gradle ':app:installDebug') {
            $installed = ((& $Adb -s $Device shell pm list packages $AppId 2>$null) -join '')
            if ($installed -match [regex]::Escape("package:$AppId")) {
                Write-Host "设备上已确认安装: $AppId" -ForegroundColor Green
                return $true
            }
            Write-Host 'Gradle 报告成功但设备上未发现该包 (模拟器可能刚开机, PMS 未就绪)。' -ForegroundColor Yellow
        }
        if ($attempt -lt $MaxAttempts) {
            Write-Host '10 秒后重试 ...' -ForegroundColor DarkGray
            Start-Sleep -Seconds 10
        }
    }
    Write-Host "安装失败: 请查看上方 Gradle 输出。常见原因: 设备未解锁/授权调试、ADB 版本过低、ABI 不匹配。" -ForegroundColor Red
    return $false
}

# ======================== App 操作 ========================

function Start-App {
    param([Parameter(Mandatory = $true)][string] $Device)
    # monkey 的参数回显走 stderr, 需 2>&1 一并吞掉
    & $Adb -s $Device shell monkey -p $AppId -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
}

function Stop-App {
    param([Parameter(Mandatory = $true)][string] $Device)
    & $Adb -s $Device shell am force-stop $AppId
}

# ======================== 权限授予 ========================

# 通过 adb 一次性授予 App 用到的运行时权限 (重装/pm clear 后会被系统重置):
#   - 通知 / 相机 / 麦克风 / 联系人 / 精确+粗略定位 (Manifest 声明的 dangerous 权限)
#   - 悬浮窗 (SYSTEM_ALERT_WINDOW, appops)
#   - 所有文件访问 (MANAGE_EXTERNAL_STORAGE, appops)
# pm grant/appops 对无 root 的 USB 真机同样可用。
function Grant-DevicePermissions {
    param([Parameter(Mandatory = $true)][string] $Device)

    Write-Host '授予运行时权限 (通知/相机/麦克风/联系人/定位 + 悬浮窗 + 所有文件访问)...' -ForegroundColor Cyan

    $runtimePermissions = @(
        'android.permission.POST_NOTIFICATIONS',
        'android.permission.CAMERA',
        'android.permission.RECORD_AUDIO',
        'android.permission.READ_CONTACTS',
        'android.permission.ACCESS_FINE_LOCATION',
        'android.permission.ACCESS_COARSE_LOCATION'
    )

    $errors = @()
    foreach ($perm in $runtimePermissions) {
        $r = (& $Adb -s $Device shell pm grant $AppId $perm 2>&1) -join ''
        if ($r -and $r -notmatch 'already granted') { $errors += "$perm => $r" }
    }
    $r = (& $Adb -s $Device shell appops set $AppId SYSTEM_ALERT_WINDOW allow 2>&1) -join ''
    if ($r) { $errors += "SYSTEM_ALERT_WINDOW => $r" }
    $r = (& $Adb -s $Device shell appops set $AppId MANAGE_EXTERNAL_STORAGE allow 2>&1) -join ''
    if ($r) { $errors += "MANAGE_EXTERNAL_STORAGE => $r" }

    if ($errors.Count -gt 0) {
        Write-Host '部分授权命令异常:' -ForegroundColor Yellow
        $errors | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkYellow }
        Write-Host '可稍后执行 .\build-client.ps1 grant 重新授权, 或在 App 内 设置 -> 权限 手动开启。' -ForegroundColor Yellow
        return $false
    }

    # 无障碍服务 (老参谋长按毫秒级识屏的前提; install/pm clear 后会被系统重置)。
    # 部分模拟器在 App 进程不存在时 settings put 会被清空: 写入后读回校验,
    # 丢失则先启动 App 再重写一次。
    $a11yService = "$AppId/com.verlintas.baic2.device.impl.a11y.Baic2AccessibilityService"
    & $Adb -s $Device shell settings put secure enabled_accessibility_services $a11yService 2>&1 | Out-Null
    & $Adb -s $Device shell settings put secure accessibility_enabled 1 2>&1 | Out-Null
    $check = ((& $Adb -s $Device shell settings get secure enabled_accessibility_services 2>$null) -join '')
    if ($check -notmatch [regex]::Escape($AppId)) {
        Write-Host '无障碍授权首次写入被系统清空, 启动 App 后重写 ...' -ForegroundColor DarkYellow
        Start-App $Device
        Start-Sleep -Seconds 3
        & $Adb -s $Device shell settings put secure enabled_accessibility_services $a11yService 2>&1 | Out-Null
        & $Adb -s $Device shell settings put secure accessibility_enabled 1 2>&1 | Out-Null
        $check = ((& $Adb -s $Device shell settings get secure enabled_accessibility_services 2>$null) -join '')
    }
    if ($check -match [regex]::Escape($AppId)) {
        Write-Host '无障碍服务已启用 (老参谋长按识屏就绪)。' -ForegroundColor Green
    }
    else {
        Write-Host '无障碍服务授权失败, 请在 设置 -> 无障碍 手动开启 BAIC2 界面自动化。' -ForegroundColor Yellow
    }

    Write-Host '权限就绪。' -ForegroundColor Green
    return $true
}

function Wait-AppPid {
    param(
        [Parameter(Mandatory = $true)][string] $Device,
        [int] $TimeoutSec = 20
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $pidValue = ((& $Adb -s $Device shell pidof $AppId 2>$null) -join '').Trim()
        if ($pidValue) { return ($pidValue -split '\s+')[0] }
        Start-Sleep -Milliseconds 800
    }
    return $null
}

# 附着日志: 找不到 PID 时退化为全量
function Invoke-AppLogcat {
    param([Parameter(Mandatory = $true)][string] $Device)

    $pidValue = Wait-AppPid $Device
    if ($pidValue) {
        Write-Host "跟踪日志 (PID=$pidValue, Ctrl+C 退出) ..." -ForegroundColor Cyan
        & $Adb -s $Device logcat --pid=$pidValue
    }
    else {
        Write-Host '未能获取应用进程 PID, 显示全部日志 (Ctrl+C 退出):' -ForegroundColor Yellow
        & $Adb -s $Device logcat
    }
}

# ======================== 老参谋冒烟 ========================

# dumpsys 按 "Window #..." 分块; 返回属于本 App 的 APPLICATION_OVERLAY 块数组。
function Get-AppOverlayBlocks {
    param([Parameter(Mandatory = $true)][string] $Device)

    $dump = (& $Adb -s $Device shell dumpsys window windows 2>$null) -join "`n"
    @(($dump -split 'Window #\d+') | Where-Object {
        $_ -match 'package=com\.verlintas\.baic2' -and $_ -match 'ty=APPLICATION_OVERLAY'
    })
}

# 悬浮球窗口中心 (块内 mAttrs=(x,y)(w x h)); 30s 内未出现返回 $null。
function Get-BallCenter {
    param([Parameter(Mandatory = $true)][string] $Device)

    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        foreach ($block in (Get-AppOverlayBlocks -Device $Device)) {
            $m = [regex]::Match($block, 'mAttrs=\{\((\d+),(\d+)\)\((\d+)x(\d+)\)')
            if ($m.Success -and [int] $m.Groups[3].Value -lt 200) {
                $x = [int] $m.Groups[1].Value; $y = [int] $m.Groups[2].Value
                $w = [int] $m.Groups[3].Value; $h = [int] $m.Groups[4].Value
                return [pscustomobject]@{ X = ($x + [int]($w / 2)); Y = ($y + [int]($h / 2)) }
            }
        }
        Start-Sleep -Seconds 2
    }
    return $null
}

# 分屏浮窗是否可见 (宽为 match_parent 的 App overlay 块)。
function Test-SplitVisible {
    param([Parameter(Mandatory = $true)][string] $Device)

    foreach ($block in (Get-AppOverlayBlocks -Device $Device)) {
        if ($block -match 'mAttrs=\{\(\d+,\d+\)\(fillx') { return $true }
    }
    return $false
}

# 扇形菜单窗口是否可见 (大于悬浮球的 App overlay 块: 扇形窗口约 300x560)。
function Test-FanMenuVisible {
    param([Parameter(Mandatory = $true)][string] $Device)

    foreach ($block in (Get-AppOverlayBlocks -Device $Device)) {
        $m = [regex]::Match($block, 'mAttrs=\{\(\d+,\d+\)\((\d+)x(\d+)\)')
        if ($m.Success -and [int] $m.Groups[1].Value -ge 250 -and [int] $m.Groups[2].Value -ge 300) {
            return $true
        }
    }
    return $false
}

# 等待扇形菜单弹出, 超时返回 $false。
function Wait-FanMenu {
    param(
        [Parameter(Mandatory = $true)][string] $Device,
        [int] $TimeoutSec = 8
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-FanMenuVisible -Device $Device) { return $true }
        Start-Sleep -Milliseconds 700
    }
    return $false
}

# 在设备上截屏并 pull 到 tests\tempImg 目录 (git 不受控; PS 的 > 重定向会写坏 PNG 二进制)。
function Save-DeviceScreenshot {
    param(
        [Parameter(Mandatory = $true)][string] $Device,
        [Parameter(Mandatory = $true)][string] $Suffix
    )
    $imgDir = Join-Path $ProjectDir "tests\tempImg"
    New-Item -ItemType Directory -Force -Path $imgDir | Out-Null
    $out = Join-Path $imgDir ("smoke-{0}-{1:yyyyMMdd-HHmmss}.png" -f $Suffix, (Get-Date))
    & $Adb -s $Device shell screencap -p /sdcard/baic2-smoke.png 2>$null | Out-Null
    & $Adb -s $Device pull /sdcard/baic2-smoke.png $out 2>$null | Out-Null
    & $Adb -s $Device shell rm /sdcard/baic2-smoke.png 2>$null | Out-Null
    return $out
}

# 等待无障碍服务绑定完成 (settings put 后 AMS 绑定需要数秒), 15s 超时。
function Wait-A11yBound {
    param([Parameter(Mandatory = $true)][string] $Device)

    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $deadline) {
        $d = (& $Adb -s $Device shell dumpsys accessibility 2>$null) -join "`n"
        if ($d -match 'Bound services:\{Service') { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

# 自动完成系统的「共享屏幕」投影授权框 (下拉选整个屏幕 -> 下一步)。
function Complete-ProjectionConsent {
    param([Parameter(Mandatory = $true)][string] $Device)

    & $Adb -s $Device shell input tap 360 580
    Start-Sleep -Milliseconds 800
    & $Adb -s $Device shell input tap 237 730
    Start-Sleep -Milliseconds 800
    & $Adb -s $Device shell input tap 563 974
    Start-Sleep -Seconds 2
}

# 老参谋冒烟: 冷启动 -> 授权 -> 等悬浮球 -> 单击验证扇形菜单 -> 收起 ->
# 长按触发屏幕问答 -> 等分屏浮窗 -> 截图。
# 产物: tests\tempImg\smoke-menu-<时间>.png + smoke-<时间>.png; 全程无交互。
# 文本路径无授权框; 像素/投影路径会自动完成系统授权框。
function Do-AdvisorSmoke {
    param([Parameter(Mandatory = $true)][string] $Device)

    # 顺序敏感: force-stop 会清掉无障碍授权, 且部分模拟器在 App 进程不存在时
    # settings put 也会被清 —— 必须先冷启动, 进程活着时再授权。
    Stop-App $Device
    Start-Sleep -Seconds 1
    Start-App $Device
    Start-Sleep -Seconds 3
    Grant-DevicePermissions -Device $Device | Out-Null
    if (-not (Wait-A11yBound -Device $Device)) {
        Write-Host '警告: 无障碍服务 15s 内未绑定, 长按将走投影授权路径。' -ForegroundColor Yellow
    }

    Write-Host '等待悬浮球窗口 ...' -ForegroundColor Cyan
    $ball = Get-BallCenter -Device $Device
    if (-not $ball) {
        Write-Host '冒烟失败: 30s 内未见悬浮球 overlay 窗口。' -ForegroundColor Red
        return $false
    }
    & $Adb -s $Device logcat -c 2>$null

    # 1) 单击 = 扇形菜单。
    Write-Host ("悬浮球就绪, 单击 ({0},{1}) 验证扇形菜单 ..." -f $ball.X, $ball.Y) -ForegroundColor Cyan
    & $Adb -s $Device shell input tap $ball.X $ball.Y
    $menuSeen = Wait-FanMenu -Device $Device
    if ($menuSeen) {
        $menuShot = Save-DeviceScreenshot -Device $Device -Suffix 'menu'
        Write-Host "扇形菜单已弹出, 截图 $menuShot" -ForegroundColor Green
    }
    else {
        Write-Host '警告: 单击后未见扇形菜单窗口 (继续长按流程)。' -ForegroundColor Yellow
    }
    # 再点球心(扇形顶点空白区=窗口空白)收起菜单, 等收回动画走完。
    & $Adb -s $Device shell input tap $ball.X $ball.Y
    Start-Sleep -Seconds 1

    # 2) 长按 = 单次参谋。adb 注入模拟不了真双击, 长按用原位 swipe 持按 600ms。
    Write-Host ("长按 ({0},{1}) 600ms 触发屏幕问答 ..." -f $ball.X, $ball.Y) -ForegroundColor Cyan
    & $Adb -s $Device shell input swipe $ball.X $ball.Y $ball.X $ball.Y 600

    # 等分屏浮窗出现; 若走投影路径, 中途自动完成系统授权框。
    $deadline = (Get-Date).AddSeconds(40)
    $splitSeen = $false
    $consentTried = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        if (Test-SplitVisible -Device $Device) { $splitSeen = $true; break }
        if (-not $consentTried) {
            $dump = (& $Adb -s $Device shell dumpsys window windows 2>$null) -join "`n"
            if ($dump -match '共享屏幕|与.*BetterAIChat2.*共享|Share screen with') {
                Write-Host '检测到投影授权框, 自动完成 ...' -ForegroundColor Cyan
                Complete-ProjectionConsent -Device $Device
                $consentTried = $true
            }
        }
    }
    Start-Sleep -Seconds 3
    $out = Save-DeviceScreenshot -Device $Device -Suffix 'split'
    if ($menuSeen -and $splitSeen) {
        Write-Host "冒烟通过: 扇形菜单 + 分屏浮窗均已出现, 截图 $out" -ForegroundColor Green
    }
    elseif ($splitSeen) {
        Write-Host "冒烟部分通过: 分屏已现但菜单未确认, 截图 $out 供人工检查。" -ForegroundColor Yellow
    }
    else {
        Write-Host "冒烟未确认分屏 (可能走授权框/文本路径失败), 截图 $out 供人工检查。" -ForegroundColor Yellow
    }
    $log = ((& $Adb -s $Device logcat -d -b crash 2>$null) -join "`n")
    if ($log -match 'FATAL EXCEPTION') { Write-Host '注意: crash buffer 有 FATAL, 请排查!' -ForegroundColor Red }
    return ($menuSeen -and $splitSeen)
}

# 触发连续参谋 (与悬浮球双击同一状态机入口): 先授权投影, 再进入差分循环。
function Do-AdvisorRecord {
    param(
        [Parameter(Mandatory = $true)][string] $Device,
        [switch] $Stop
    )
    if ($Stop) {
        # 停止: 服务 action (与双击同一状态机路径, 不受 adb 注入抖动影响)。
        & $Adb -s $Device shell am start-foreground-service -a 'com.verlintas.baic2.floatball.STOP_RECORDING' -n "$AppId/.floatball.FloatBallService" 2>&1 | Out-Null
        Start-Sleep -Seconds 2
        Write-Host '已触发停止连续参谋。' -ForegroundColor Green
        return
    }
    & $Adb -s $Device shell am start-foreground-service -a 'com.verlintas.baic2.floatball.START_RECORDING' -n "$AppId/.floatball.FloatBallService" 2>&1 | Out-Null
    Write-Host '已触发连续参谋 (首次会弹系统投影授权框, 请在设备上确认)。' -ForegroundColor Green
}

# ======================== 工作流 ========================

# 一键运行 (等价 Android Studio Run): 增量编译安装(带校验/重试) -> 授权 -> 启动 -> (可选)日志
# DeepSeek 开发密钥由 DevAgentSeeder 在 App 首启自动预置, 脚本不介入 App 内配置。
function Invoke-RunWorkflow {
    param(
        [Parameter(Mandatory = $true)][string] $Device,
        [switch] $AttachLog
    )

    if (-not (Install-DebugApp -Device $Device)) { return $false }

    # 重装会重置运行时权限, 启动前先通过 adb 恢复, 做到「装完即用」
    Grant-DevicePermissions -Device $Device | Out-Null

    & $Adb -s $Device logcat -c
    Start-App $Device
    Write-Host "$AppId 已安装并启动。" -ForegroundColor Green
    Write-Host 'DeepSeek 开发密钥已在 App 首启自动预置 (debug 构建); 其他 Agent 可在 App 内配置。' -ForegroundColor DarkGray

    if ($AttachLog) { Invoke-AppLogcat -Device $Device }
    return $true
}

# ======================== 菜单动作 ========================

function Do-BuildDebug { Invoke-Gradle ':app:assembleDebug' }
function Do-BuildRelease { Invoke-Gradle ':app:assembleRelease' }
function Do-RebuildDebug {
    if (Invoke-Gradle 'clean') { Invoke-Gradle ':app:assembleDebug' }
}
function Do-Clean { Invoke-Gradle 'clean' }
function Do-Test { Invoke-Gradle ':app:testDebugUnitTest' }

function Do-StartEmulator {
    $existing = Get-ConnectedDevice
    if ($existing) {
        Write-Host "已有在线设备: $existing, 无需重复启动。" -ForegroundColor Yellow
        return
    }

    # 汇总可启动目标: 先雷电多开实例, 再 AVD
    $targets = @()
    $ldDir = Get-LdPlayerDir
    if ($ldDir) {
        foreach ($ld in Get-LdPlayerInstances -LdDir $ldDir) {
            $state = if ($ld.Running) { ' (运行中, 仅连接)' } else { '' }
            $targets += [pscustomobject]@{
                Kind    = 'ld'
                Label   = "雷电模拟器 $($ld.Title) [实例$($ld.Index)]$state"
                LdIndex = $ld.Index
                LdDir   = $ldDir
            }
        }
    }
    foreach ($a in Get-AvdList) {
        $targets += [pscustomobject]@{ Kind = 'avd'; Label = "Android AVD $a"; AvdName = $a }
    }

    if ($targets.Count -eq 0) {
        Write-Host "未找到任何 AVD 或雷电模拟器实例 (emulator: $Emulator)。" -ForegroundColor Yellow
        return
    }
    if ($targets.Count -eq 1) {
        $t = $targets[0]
        if ($t.Kind -eq 'ld') { Start-LdPlayerInstance -Index $t.LdIndex -LdDir $t.LdDir | Out-Null }
        else { Start-Avd -AvdName $t.AvdName | Out-Null }
        return
    }
    for ($i = 0; $i -lt $targets.Count; $i++) {
        Write-Host (" {0}) {1}" -f ($i + 1), $targets[$i].Label)
    }
    Write-Host ' 0) 取消'
    $sel = Read-Host '选择要启动的模拟器'
    $index = 0
    if ([int]::TryParse($sel, [ref]$index) -and $index -ge 1 -and $index -le $targets.Count) {
        $t = $targets[$index - 1]
        if ($t.Kind -eq 'ld') { Start-LdPlayerInstance -Index $t.LdIndex -LdDir $t.LdDir | Out-Null }
        else { Start-Avd -AvdName $t.AvdName | Out-Null }
    }
    elseif ($sel -ne '0') {
        Write-Host "无效选择: $sel" -ForegroundColor Yellow
    }
}

function Do-KillEmulator {
    $emulators = (& $Adb devices) -match '^emulator-\d+' | ForEach-Object { ($_ -split "`t")[0] }
    if (-not $emulators) {
        Write-Host '未发现运行中的模拟器。' -ForegroundColor Yellow
        return
    }
    foreach ($emu in $emulators) {
        & $Adb -s $emu emu kill
        Write-Host "已关闭模拟器: $emu" -ForegroundColor Green
    }
}

function Do-OpenApkDir {
    $apkDir = Join-Path $ProjectDir 'app\build\outputs\apk\debug'
    if (Test-Path $apkDir) {
        Start-Process explorer.exe $apkDir
    }
    else {
        Write-Host "APK 输出目录尚不存在, 请先构建: $apkDir" -ForegroundColor Yellow
    }
}

# ---------- 子菜单: 设备 ----------

function Show-DeviceMenu {
    while ($true) {
        Write-Header
        Write-Host ' -- 设备 --' -ForegroundColor Cyan
        Write-Host ' 1) 建立 adb reverse 8080 (App 访问宿主机本机服务用)'
        Write-Host ' 2) 启动模拟器 (雷电 / AVD, 可选择)'
        Write-Host ' 3) 关闭模拟器'
        Write-Host ' 4) 停止 App'
        Write-Host ' 5) 清除 App 数据 (会清空 App 内 Agent/密钥配置, 并自动重新授予权限)'
        Write-Host ' 6) 重新授予运行时权限 (通知/相机/麦克风/联系人/定位/悬浮窗/存储)'
        Write-Host ' 0) 返回主菜单'
        Write-Host '========================================='
        $sel = Read-Host '选择 (0 返回)'
        if ($null -eq $sel) { return }  # stdin EOF, 避免空转死循环
        switch ($sel) {
            '1' {
                $device = Ensure-Device
                if ($device) { Enable-ServerReverse -Device $device | Out-Null }
            }
            '2' { Do-StartEmulator }
            '3' { Do-KillEmulator }
            '4' {
                $device = Ensure-Device
                if ($device) { Stop-App $device; Write-Host "已停止 $AppId" -ForegroundColor Green }
            }
            '5' {
                $device = Ensure-Device
                if ($device) {
                    $confirm = Read-Host "确认清除 $AppId 全部数据? (y/N)"
                    if ($confirm -eq 'y' -or $confirm -eq 'Y') {
                        & $Adb -s $device shell pm clear $AppId | Out-Null
                        Write-Host '已清除。' -ForegroundColor Green
                        Grant-DevicePermissions -Device $device | Out-Null
                        Write-Host '下次启动 App 时 DeepSeek 开发密钥会自动重新预置。' -ForegroundColor DarkGray
                    }
                }
            }
            '6' {
                $device = Ensure-Device
                if ($device) { Grant-DevicePermissions -Device $device | Out-Null }
            }
            '0' { return }
            default { Write-Host "无效选择: $sel" -ForegroundColor Yellow }
        }
        if ($sel -eq '0') { break }
        Pause-ForMenu
    }
}

# ---------- 子菜单: 高级 ----------

function Show-AdvancedMenu {
    while ($true) {
        Write-Header
        Write-Host ' -- 高级 (低频: 耗时长或仅发布前使用) --' -ForegroundColor Cyan
        Write-Host ' 1) clean 清理构建产物'
        Write-Host ' 2) 清理后全量重建 Debug (耗时数分钟)'
        Write-Host ' 3) 构建 Release APK (生产配置, 不含开发密钥)'
        Write-Host ' 4) 安装 Release (:app:installRelease)'
        Write-Host ' 5) 打开 APK 输出目录'
        Write-Host ' 0) 返回主菜单'
        Write-Host '========================================='
        $sel = Read-Host '选择 (0 返回)'
        if ($null -eq $sel) { return }  # stdin EOF, 避免空转死循环
        switch ($sel) {
            '1' { Do-Clean }
            '2' { Do-RebuildDebug }
            '3' {
                if (-not (Show-ReleaseSigningWarning)) {
                    $confirm = Read-Host '仍要继续构建未签名包? (y/N)'
                    if ($confirm -notin @('y', 'Y')) { break }
                }
                if (Invoke-KotlinCheck) { Do-BuildRelease }
            }
            '4' {
                if (-not (Show-ReleaseSigningWarning)) {
                    Write-Host '已取消: 未签名 APK 无法安装, 请先配置签名凭据。' -ForegroundColor Yellow
                    break
                }
                $device = Ensure-Device
                if ($device) { Invoke-Gradle ':app:installRelease' | Out-Null }
            }
            '5' { Do-OpenApkDir }
            '0' { return }
            default { Write-Host "无效选择: $sel" -ForegroundColor Yellow }
        }
        if ($sel -eq '0') { break }
        Pause-ForMenu
    }
}

# ---------- 主菜单 ----------

function Show-MainMenu {
    while ($true) {
        Write-Header
        Write-Host ' 1) 一键运行: 增量编译安装 + 授权 + 启动 + 全量日志  (回车默认, 等同 Android Studio Run)'
        Write-Host ' 2) 仅看日志: App 全量'
        Write-Host ' 3) 构建 Debug APK (仅打包, 不安装)'
        Write-Host ' 4) 单元测试 (:app:testDebugUnitTest)'
        Write-Host ' 5) 设备 (adb reverse / 模拟器 / 停止 / 清数据 / 授权)'
        Write-Host ' 6) 高级 (clean / 全量重建 / Release / APK 目录)'
        Write-Host ' 0) 退出'
        Write-Host '========================================='
        $choice = Read-Host '输入数字并回车 (0 退出)'
        # stdin 到达 EOF (管道输入结束等非交互场景) 时 Read-Host 返回 $null,
        # 直接进 switch 会在 default 分支空转死循环; 按退出处理。
        if ($null -eq $choice) { break }
        if ($choice -eq '') { $choice = '1' }

        switch ($choice) {
            '1' {
                if (-not (Invoke-KotlinCheck)) { break }
                $device = Ensure-Device
                if ($device) { Invoke-RunWorkflow -Device $device -AttachLog | Out-Null }
            }
            '2' {
                $device = Ensure-Device
                if ($device) {
                    Start-App $device
                    Invoke-AppLogcat -Device $device | Out-Null
                }
            }
            '3' { if (Invoke-KotlinCheck) { Do-BuildDebug } }
            '4' { if (Invoke-KotlinCheck) { Do-Test } }
            '5' { Show-DeviceMenu }
            '6' { Show-AdvancedMenu }
            '0' { break }
            default { Write-Host "无效选择: $choice" -ForegroundColor Yellow }
        }

        if ($choice -eq '0') { break }
        if ($choice -in @('5', '6')) { continue }
        Pause-ForMenu
    }
}

# ======================== 非交互命令分发 ========================

function Show-Help {
    Write-Host @'
用法:
  .\build-client.ps1 [命令] [-NoLog]

  构建类命令 (dev/build/test/release 等) 先过 Kotlin 编译门禁, 语法不合格立即终止;
  rebuild (clean+全量重编) 与 clean 本身即编译检查, 不设前置。
命令:
  dev / deploy / quick  增量编译安装 + 授权 + 启动 + 全量日志 (等同 Android Studio Run)
  log                   附着 App 全量日志
  reverse               建立 adb reverse 8080 (App 访问宿主机本机服务)
  stop                  停止 App
  clear-data            清除 App 数据 (随后自动重新授予权限; 密钥下次首启自动重置)
  grant                 重新授予运行时权限 (通知/相机/麦克风/联系人/定位/悬浮窗/存储/无障碍)
  smoke                 老参谋冒烟: 授权+冷启动+单击验菜单+长按问屏+等分屏+截图 (tests\tempImg\smoke-*.png)
  record                触发连续参谋 (首次需在设备上确认投影授权)
  record-stop           快速双击悬浮球停止连续参谋
  start-emu             启动第一个 AVD (无设备时)
  kill-emu              关闭运行中的模拟器
  build                 仅构建 Debug APK (:app:assembleDebug)
  test                  单元测试
  clean                 gradle clean
  rebuild               clean + assembleDebug (全量, 慎用)
  release               构建 Release APK (需签名凭据, 缺失时警告; 不含开发密钥)
  install-release       安装 Release (需签名凭据, 缺失时终止)
  apk                   打开 APK 输出目录
  menu / help           交互菜单 / 本帮助

开发环境 DeepSeek 密钥:
  在 local.properties (已被 gitignore) 写入:
    dev.deepseekApiKey=sk-xxx
  debug 构建自动注入, App 首启由 DevAgentSeeder 预置为默认 Agent, 免手动配置。
  Release 构建恒不注入, 生产环境不受影响。

模拟器访问宿主机服务:
  Android 模拟器直接用 10.0.2.2:<port>; USB 真机先执行 .\build-client.ps1 reverse。

部署目标 (交互菜单选择 "一键运行" 时列出):
  真机       USB 连接并开启 USB 调试后自动出现
  雷电模拟器 自动从注册表/常见目录发现, 多开实例逐个列出, 未运行自动启动
  Android AVD 多个 AVD 全部列出供选择

示例:
  .\build-client.ps1 dev
  .\build-client.ps1 dev -NoLog
  .\build-client.ps1 log
'@
}

function Invoke-CommandLine {
    switch ($Command) {
        'help' { Show-Help; return }
        'menu' { Show-MainMenu; return }
    }

    if (-not (Test-Path (Join-Path $ProjectDir 'gradlew.bat'))) {
        Write-Host "未找到 gradlew.bat, 请确认工程目录: $ProjectDir" -ForegroundColor Red
        exit 1
    }

    # release 签名预检放在最前: 缺凭据时第一时间告知, 不等构建完才发现白跑。
    # release 仅警告后继续 (未签名包仍可用于 CI 二次签名); install-release 直接
    # 终止 —— 未签名 APK 必然安装失败。
    if ($Command -eq 'release' -and -not (Show-ReleaseSigningWarning)) {
        Write-Host '继续构建 (产物为未签名 APK) ...' -ForegroundColor DarkYellow
    }
    if ($Command -eq 'install-release' -and -not (Show-ReleaseSigningWarning)) {
        Write-Host '已终止: 未签名 APK 无法安装, 请先配置签名凭据 (见上方指引)。' -ForegroundColor Red
        exit 1
    }

    # 构建类命令先过 Kotlin 语法门禁, 不合格立即停止 —— 且放在选设备/启动
    # 模拟器之前, 避免代码坏了还白白拉起模拟器。rebuild 本身即全量重编,
    # clean 无编译, 二者不设前置。
    $needsCheck = @('dev', 'deploy', 'quick', 'build', 'test', 'release', 'install-release')
    if ($needsCheck -contains $Command -and -not (Invoke-KotlinCheck)) { exit 1 }

    # 以下命令需要设备
    $needsDevice = @('dev', 'deploy', 'quick', 'log', 'reverse', 'stop', 'clear-data', 'grant', 'smoke', 'record', 'record-stop', 'start-emu')
    $device = $null
    if ($needsDevice -contains $Command) {
        $device = Ensure-Device -AutoStartAvd
        if (-not $device) { exit 1 }
    }

    switch ($Command) {
        { $_ -in @('dev', 'deploy', 'quick') } {
            $ok = Invoke-RunWorkflow -Device $device -AttachLog:(-not $NoLog)
            if (-not $ok) { exit 1 }
        }
        'log' { Start-App $device; Invoke-AppLogcat -Device $device }
        'reverse' { Enable-ServerReverse -Device $device | Out-Null }
        'stop' { Stop-App $device }
        'clear-data' {
            & $Adb -s $device shell pm clear $AppId | Out-Null
            Write-Host '已清除。下次启动 App 时 DeepSeek 开发密钥会自动重新预置。' -ForegroundColor Green
            Grant-DevicePermissions -Device $device | Out-Null
        }
        'grant' { Grant-DevicePermissions -Device $device | Out-Null }
        'smoke' { if (-not (Do-AdvisorSmoke -Device $device)) { exit 1 } }
        'record' { Do-AdvisorRecord -Device $device }
        'record-stop' { Do-AdvisorRecord -Device $device -Stop }
        'kill-emu' { Do-KillEmulator }
        'build' { if (-not (Do-BuildDebug)) { exit 1 } }
        'test' { if (-not (Do-Test)) { exit 1 } }
        'clean' { if (-not (Do-Clean)) { exit 1 } }
        'rebuild' { if (-not (Do-RebuildDebug)) { exit 1 } }
        'release' { if (-not (Do-BuildRelease)) { exit 1 } }
        'install-release' {
            if (-not (Invoke-Gradle ':app:installRelease')) { exit 1 }
        }
        'apk' { Do-OpenApkDir }
    }
}

# 被 dot-source 时 (单元测试) 不自动进入主流程
if ($MyInvocation.InvocationName -ne '.') {
    Invoke-CommandLine
}
