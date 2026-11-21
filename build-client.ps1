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

    Android 模拟器 / BlueStacks 模拟器 (多开实例) / USB 真机统一支持:
    非交互命令 (dev 等) 已连设备直接用, 否则自动启动第一个 AVD/BlueStacks 实例;
    交互模式会先列出全部部署目标, 启动任何模拟器前都会确认。

.EXAMPLE
    .\build-client.ps1                  # 交互菜单 (直接回车 = 编译安装并启动)
    .\build-client.ps1 dev              # 非交互: 增量安装 + 启动 + 全量日志
    .\build-client.ps1 dev -NoLog       # 安装启动, 不附着日志
    .\build-client.ps1 dev -Device 449c98301422   # 多设备并存时显式指定真机
    .\build-client.ps1 log              # 仅看 App 日志
    .\build-client.ps1 build            # 仅构建 Debug APK
    .\build-client.ps1 test             # 单元测试
    .\build-client.ps1 help             # 全部命令
    .\build-client.ps1 ci -Commit -Install   # 发版并自动装到 1.100 模拟器
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
        'apk',
        'ci', 'ci-status', 'ci-download', 'ci-retag', 'ci-install', 'ci-login'
    )]
    [string] $Command = 'menu',

    # 显式指定部署目标序列号 (adb devices 中的 serial)。多设备并存
    # (真机+模拟器/多开) 时优先于自动选择; 单设备可省略 (自动优先真机)。
    [string] $Device = '',

    # 部署后不附着 logcat
    [switch] $NoLog,

    # ---- GitLab CI 远程发版 (ci / ci-retag / ci-install 使用) ----

    # 版本号 vX.Y.Z; ci 缺省自动取「已有最大补丁号 +1」
    [string] $Version = '',

    # ci: 允许把工作区全部改动提交进发版提交 (默认脏工作区直接拒绝)
    [switch] $Commit,

    # ci: 发版提交说明 (缺省 chore(release): <版本>)
    [string] $ReleaseMessage = '',

    # ci: 流水线通过后直接安装到 1.100 的模拟器
    [switch] $Install
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

# ======================== GitLab CI 远程发布 ========================

# 1.100 上的 GitLab + Docker Runner: 推送 vX.Y.Z tag 自动跑 verify -> build -> device,
# 签名包作为 build 作业产物保留 30 天。GitLab 侧不存任何密钥。
$GitLabHost = 'http://192.168.1.101'
$GitLabProjectId = 69
$GitLabRemote = 'gitlab'          # 本仓库指向 GitLab 的 remote 名
$GitLabBranch = 'laocanmou'
# PAT 只存本机 Secrets 仓 (ci/gitlab-pat.env, scope=api), 工程仓库不落任何密钥
$GitLabTokenFile = 'D:\work\Secrets\ci\gitlab-pat.env'
# 构建产物落本机 releases/, 已被 .gitignore 整目录忽略
$ReleaseDir = Join-Path $ProjectDir 'releases'
# 1.100 的模拟器 sidecar 只监听容器回环 5555, 本机经 SSH 隧道访问
$RemoteEmulatorSshHost = 'moni-cibuild'
$RemoteEmulatorLocalPort = 15555
# 等流水线上限 (秒): 冷缓存 verify ~15 分钟, 留足余量
$CiTimeoutSec = 2700

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

# ======================== BlueStacks 模拟器 ========================

# BlueStacks 5 (Nxt) 主程序为 HD-Player.exe, 多开实例以 --instance <名称> 启动。
# 安装路径优先从注册表 InstallDir 解析, 找不到再试常见目录。结果缓存, 避免重复扫描。
$script:BstDirCache = $null
$script:BstDirResolved = $false
function Get-BstDir {
    if ($script:BstDirResolved) { return $script:BstDirCache }
    $script:BstDirResolved = $true

    $candidates = @()
    foreach ($key in @(
        'HKLM:\SOFTWARE\BlueStacks_nxt_cn', 'HKLM:\SOFTWARE\BlueStacks_nxt',
        'HKLM:\SOFTWARE\WOW6432Node\BlueStacks_nxt_cn', 'HKLM:\SOFTWARE\WOW6432Node\BlueStacks_nxt'
    )) {
        $v = (Get-ItemProperty $key -ErrorAction SilentlyContinue).InstallDir
        if ($v) { $candidates += $v }
    }
    $candidates += @(
        'D:\Programs\BlueStacks_nxt_cn',
        "$env:ProgramFiles\BlueStacks_nxt_cn", "$env:ProgramFiles\BlueStacks_nxt",
        'C:\Program Files\BlueStacks_nxt_cn', 'C:\Program Files\BlueStacks_nxt'
    )
    foreach ($cand in $candidates) {
        if ($cand -and (Test-Path (Join-Path $cand 'HD-Player.exe'))) {
            $script:BstDirCache = $cand.TrimEnd('\')
            return $script:BstDirCache
        }
    }
    return $null
}

# BlueStacks 数据目录 (含 bluestacks.conf 与 Engine\<实例>\ 磁盘文件)。
# CN 版注册表: UserDefinedDir=数据根目录, DataDir=<根>\Engine; 国际版为 UserDir。
# 依次尝试, 全部缺失退回 ProgramData 常见位置。
function Get-BstDataDir {
    foreach ($key in @(
        'HKLM:\SOFTWARE\BlueStacks_nxt_cn', 'HKLM:\SOFTWARE\BlueStacks_nxt',
        'HKLM:\SOFTWARE\WOW6432Node\BlueStacks_nxt_cn', 'HKLM:\SOFTWARE\WOW6432Node\BlueStacks_nxt'
    )) {
        $p = Get-ItemProperty $key -ErrorAction SilentlyContinue
        if (-not $p) { continue }
        foreach ($v in @($p.UserDefinedDir, $p.UserDir)) {
            if ($v -and (Test-Path $v)) { return $v.TrimEnd('\') }
        }
        if ($p.DataDir) {
            $dd = $p.DataDir.TrimEnd('\')
            $root = if ((Split-Path $dd -Leaf) -eq 'Engine') { Split-Path $dd -Parent } else { $dd }
            if (Test-Path $root) { return $root }
        }
    }
    foreach ($cand in @('C:\ProgramData\BlueStacks_nxt_cn', 'C:\ProgramData\BlueStacks_nxt')) {
        if (Test-Path $cand) { return $cand }
    }
    return $null
}

# 解析 bluestacks.conf, 返回全部实例名。
# conf 每行形如 bst.instance.<名>.<键>=<值> (另有 status.* 运行时子组, 需排除);
# 顶层键均为扁平形态, 用贪婪匹配取最后一个点之前的部分作实例名。
function Get-BstInstanceNames {
    param([Parameter(Mandatory = $true)][string] $ConfPath)

    $names = [ordered] @{}
    foreach ($line in (Get-Content $ConfPath -ErrorAction SilentlyContinue)) {
        if ($line -notmatch '^bst\.instance\.') { continue }
        if ($line -match '^bst\.instance\.[^.]+\.status\.') { continue }
        if ($line -match '^bst\.instance\.(.+)\.[A-Za-z_]+\s*=') { $names[$Matches[1]] = $true }
    }
    @($names.Keys)
}

# conf 中字符串键的值 (去引号); 不存在返回 $null
function Get-BstConfValue {
    param(
        [Parameter(Mandatory = $true)][string] $ConfPath,
        [Parameter(Mandatory = $true)][string] $Key
    )
    $line = (Get-Content $ConfPath -ErrorAction SilentlyContinue |
        Where-Object { $_ -match ('^' + [regex]::Escape($Key) + '\s*=') } |
        Select-Object -First 1)
    if ($line -match '=\s*"([^"]*)"') { return $Matches[1] }
    return $null
}

# 实例的 adb 端口: conf 键 bst.instance.<名>.adb_port, 缺省 5555。
# (BlueStacks 同时只跑一个实例时端口即 5555; 多开并发端口会变, 开发流程不涉及。)
function Get-BstAdbPort {
    param([string] $Name = '')

    if (-not $Name) { return 5555 }
    $dataDir = Get-BstDataDir
    if (-not $dataDir) { return 5555 }
    foreach ($conf in @((Join-Path $dataDir 'bluestacks.conf'), (Join-Path $dataDir "Engine\$Name\bluestacks.conf"))) {
        if (-not (Test-Path $conf)) { continue }
        $v = Get-BstConfValue -ConfPath $conf -Key "bst.instance.$Name.adb_port"
        if ($v -match '^\d+$') { return [int] $v }
    }
    return 5555
}

# 列出实例及运行状态。运行判定: HD-Player.exe 进程命令行含 --instance <名>;
# 无 --instance 参数的 HD-Player 进程视为默认实例在跑。
# 实例来源: conf 键前缀; 刚安装尚无 conf 时退回 Engine 下含 <名>.bstk 的目录;
# 仍无则返回一个空名条目, 用于直接启动 HD-Player.exe。
function Get-BstInstances {
    param([Parameter(Mandatory = $true)][string] $BstDir)

    $names = @()
    $dataDir = Get-BstDataDir
    if ($dataDir) {
        $conf = Join-Path $dataDir 'bluestacks.conf'
        if (Test-Path $conf) { $names = @(Get-BstInstanceNames -ConfPath $conf) }
        if ($names.Count -eq 0) {
            $engine = Join-Path $dataDir 'Engine'
            if (Test-Path $engine) {
                $names = @(Get-ChildItem $engine -Directory -ErrorAction SilentlyContinue |
                    Where-Object { Test-Path (Join-Path $_.FullName "$($_.Name).bstk") } |
                    ForEach-Object { $_.Name })
            }
        }
    }
    if ($names.Count -eq 0) { $names = @('') }

    $conf2 = if ($dataDir) { Join-Path $dataDir 'bluestacks.conf' } else { $null }
    $procNames = @{}
    foreach ($p in (Get-CimInstance Win32_Process -Filter "Name = 'HD-Player.exe'" -ErrorAction SilentlyContinue)) {
        if ($p.CommandLine -match '--instance\s+"?([^"\s]+)"?') { $procNames[$Matches[1]] = $true }
        else { $procNames['__default__'] = $true }
    }
    @(foreach ($n in $names) {
        $running = if ($n) { $procNames.ContainsKey($n) } else { $procNames.Count -gt 0 }
        $display = if ($n -and $conf2 -and (Test-Path $conf2)) { Get-BstConfValue -ConfPath $conf2 -Key "bst.instance.$n.display_name" } else { $null }
        [pscustomobject]@{ Name = $n; DisplayName = $display; Running = $running }
    })
}

# 实例的显示名: 优先 conf 的 display_name, 空名实例 (尚无实例) 显示「默认实例」
function Get-BstLabel {
    param([AllowEmptyString()][string] $Name, [string] $DisplayName)
    if ($DisplayName) { return $DisplayName }
    if ($Name) { return $Name }
    return '默认实例'
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
        $kind = if ($online -match '^emulator-|^127\.0\.0\.1:') { '模拟器' } else { '实体设备' }
        $model = ((& $Adb -s $online shell getprop ro.product.model 2>$null) -join '').Trim()
        $rel = ((& $Adb -s $online shell getprop ro.build.version.release 2>$null) -join '').Trim()
        $label = if ($model) { "$model (Android $rel)" } else { '' }
        Write-Host (" 当前设备: {0} ({1}) {2}" -f $online, $kind, $label) -ForegroundColor Green
    }
    else {
        $avdCount = @(Get-AvdList).Count
        $bstNote = if (Get-BstDir) { ' | BlueStacks: 已检测' } else { '' }
        Write-Host " 当前设备: 无 (可用 AVD: $avdCount 个$bstNote)" -ForegroundColor Yellow
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

# 启动 (或连接已在运行的) BlueStacks 实例, 等待 boot_completed, 返回 adb 设备序列号。
# BlueStacks 的 adb 是 TCP 端口 (默认 5555), 不会被 SDK adb 自动发现, 需 adb connect。
# 前提: BlueStacks 设置 -> 高级 -> 「Android 调试桥」已开启。
function Start-BstInstance {
    param(
        [Parameter(Mandatory = $true)][string] $BstDir,
        [string] $Name = ''
    )

    $port = Get-BstAdbPort -Name $Name
    $serial = "127.0.0.1:$port"

    $inst = Get-BstInstances -BstDir $BstDir | Where-Object { $_.Name -eq $Name } | Select-Object -First 1
    $title = Get-BstLabel -Name $Name -DisplayName $inst.DisplayName
    if ($inst -and -not $inst.Running) {
        Write-Host "正在启动 BlueStacks ($title) ..." -ForegroundColor Cyan
        $launchArgs = if ($Name) { @('--instance', $Name) } else { @() }
        Start-Process -FilePath (Join-Path $BstDir 'HD-Player.exe') -ArgumentList $launchArgs | Out-Null
    }
    else {
        Write-Host "BlueStacks $title 已在运行, 尝试通过 adb 连接 ..." -ForegroundColor Cyan
    }

    $deadline = (Get-Date).AddSeconds($BootTimeoutSec)

    # 1) adb connect 后等待设备上线
    $device = $null
    while ((Get-Date) -lt $deadline) {
        & $Adb connect $serial 2>$null | Out-Null
        $device = Get-AdbDeviceSerials | Where-Object { $_ -eq $serial } | Select-Object -First 1
        if ($device) { break }
        Start-Sleep -Seconds 2
    }
    if (-not $device) {
        Write-Host "等待 BlueStacks 上线超时 (${BootTimeoutSec}s)。" -ForegroundColor Red
        Write-Host '请确认 BlueStacks 设置 -> 高级 -> 「Android 调试桥」已开启。' -ForegroundColor Yellow
        return $null
    }
    Write-Host "设备已上线: $device, 等待系统启动完成 ..." -ForegroundColor Cyan

    & $Adb -s $device wait-for-device | Out-Null

    # 2) 等待 boot_completed
    while ((Get-Date) -lt $deadline) {
        # adb 输出为空/多行数组时直接 .Trim() 会报错, 先 -join '' 归一为字符串
        $booted = ((& $Adb -s $device shell getprop sys.boot_completed 2>$null) -join '').Trim()
        if ($booted -eq '1') {
            Write-Host "BlueStacks 启动完成: $device" -ForegroundColor Green
            return $device
        }
        Start-Sleep -Seconds 2
    }

    Write-Host "等待 BlueStacks 开机完成超时 (${BootTimeoutSec}s)。" -ForegroundColor Red
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
#   BlueStacks 多开实例 (未运行则先拉起并等待开机)
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

    # BlueStacks 实例: 无论是否已在 adb 列表都列出 (实例可靠入口, 未 connect 的也可选)
    $bstDir = Get-BstDir
    if ($bstDir) {
        foreach ($bst in Get-BstInstances -BstDir $bstDir) {
            $state = if ($bst.Running) { '运行中, adb 连接后部署' } else { '未运行, 启动后部署' }
            $labelName = Get-BstLabel -Name $bst.Name -DisplayName $bst.DisplayName
            $items += [pscustomobject]@{
                Kind    = 'bst'
                Label   = ("BlueStacks {0} ({1})" -f $labelName, $state)
                BstName = $bst.Name
                BstDir  = $bstDir
            }
        }
    }

    # -- 3) Android AVD (跳过已在运行的; BlueStacks 不识别 emu avd name 时返回空, 无害) --
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
        Write-Host '未找到任何可用目标: 请用 USB 连接手机并开启 USB 调试、启动/安装 BlueStacks, 或先创建 AVD。' -ForegroundColor Yellow
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
        'bst' { return Start-BstInstance -BstDir $item.BstDir -Name $item.BstName }
        'avd' { return Start-Avd -AvdName $item.AvdName }
    }
    return $null
}

# 确保有可用设备并返回序列号:
#   交互模式 (默认)   : 弹统一目标选择 (真机 / BlueStacks / AVD), 启动任何模拟器前一律手动确认;
#   非交互 -AutoStartAvd (如 dev 命令): 已连设备直接用, 否则自动启动第一个 AVD, 再退而求其次启动 BlueStacks 实例。
function Ensure-Device {
    param([switch] $AutoStartAvd)

    # 显式指定优先: -Device 序列号必须真实在线 (授权缺失/离线直接报错, 不静默换机)
    if ($Device) {
        $serials = @(Get-AdbDeviceSerials)
        if ($serials -contains $Device) { return $Device }
        Write-Host "指定的设备 '$Device' 不在 adb devices 在线列表中 (可能未授权/离线):" -ForegroundColor Red
        & $Adb devices | Write-Host
        return $null
    }

    if (-not $AutoStartAvd) { return Select-Device }

    $device = Get-ConnectedDevice
    if ($device) { return $device }

    $avds = @(Get-AvdList)
    if ($avds) {
        Write-Host "未检测到设备, 自动启动 AVD: $($avds[0])" -ForegroundColor Yellow
        return Start-Avd -AvdName $avds[0]
    }

    $bstDir = Get-BstDir
    if ($bstDir) {
        $bsts = @(Get-BstInstances -BstDir $bstDir)
        if ($bsts.Count -gt 0) {
            $first = $bsts[0]
            $labelName = Get-BstLabel -Name $first.Name -DisplayName $first.DisplayName
            Write-Host "未检测到设备, 自动启动 BlueStacks ($labelName) ..." -ForegroundColor Yellow
            return Start-BstInstance -BstDir $bstDir -Name $first.Name
        }
    }

    Write-Host '没有已连接的设备, 也没有找到 AVD 或 BlueStacks。请用 USB 连接手机 (开启 USB 调试)。' -ForegroundColor Yellow
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
        elseif ($Device -notmatch '^emulator-|^127\.0\.0\.1:') {
            Write-Host '真机安装失败 (如 INSTALL_FAILED_USER_RESTRICTED): 请看手机屏幕是否弹「USB 安装」确认框, 点允许;' -ForegroundColor Yellow
            Write-Host '或 MIUI 开发者选项里开启「USB 安装」后重试。' -ForegroundColor Yellow
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

    # POST_NOTIFICATIONS 仅 API 33+ 存在; 低版本设备 (如 BlueStacks API 30) pm grant
    # 会抛 Unknown permission, 不能让它进 $errors 中断后续 appops/无障碍授权。
    $runtimePermissions = @(
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
    $apiLevel = 0
    [void] [int]::TryParse(((& $Adb -s $Device shell getprop ro.build.version.sdk 2>$null) -join '').Trim(), [ref]$apiLevel)
    if ($apiLevel -ge 33) {
        $r = (& $Adb -s $Device shell pm grant $AppId android.permission.POST_NOTIFICATIONS 2>&1) -join ''
        if ($r -and $r -notmatch 'already granted') { $errors += "POST_NOTIFICATIONS => $r" }
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

    # 无障碍服务 (好参谋长按毫秒级识屏的前提; install/pm clear 后会被系统重置)。
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
        Write-Host '无障碍服务已启用 (好参谋长按识屏就绪)。' -ForegroundColor Green
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

# ======================== 好参谋冒烟 ========================

# dumpsys 按 "Window #..." 分块; 返回属于本 App 的 APPLICATION_OVERLAY 块数组。
function Get-AppOverlayBlocks {
    param([Parameter(Mandatory = $true)][string] $Device)

    $dump = (& $Adb -s $Device shell dumpsys window windows 2>$null) -join "`n"
    @(($dump -split 'Window #\d+') | Where-Object {
        $_ -match 'package=com\.verlintas\.baic2' -and $_ -match 'ty=APPLICATION_OVERLAY'
    })
}

# 悬浮球窗口中心; 30s 内未出现返回 $null。
# 优先真实 frame（API 35+ 上 mAttrs 坐标未计 inset，与可触区偏差约 48px）；
# 窗口刚创建时 frame 可能缺/为 0x0——此时不回退 mAttrs，循环等 layout 完成再取，
# 仅超时后才用 mAttrs 兜底（坐标可能偏 inset，仅比拿不到坐标强）。
function Get-BallCenter {
    param([Parameter(Mandatory = $true)][string] $Device)

    $deadline = (Get-Date).AddSeconds(30)
    $attrsCenter = $null
    while ((Get-Date) -lt $deadline) {
        foreach ($block in (Get-AppOverlayBlocks -Device $Device)) {
            $f = [regex]::Match($block, 'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
            if (-not $f.Success) {
                $f = [regex]::Match($block, 'frame=\[Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)\]')
            }
            if ($f.Success) {
                $l = [int] $f.Groups[1].Value; $t = [int] $f.Groups[2].Value
                $r = [int] $f.Groups[3].Value; $b = [int] $f.Groups[4].Value
                if (($r - $l) -gt 20 -and ($r - $l) -lt 200 -and ($b - $t) -gt 20 -and ($b - $t) -lt 200) {
                    return [pscustomobject]@{ X = [int](($l + $r) / 2); Y = [int](($t + $b) / 2) }
                }
            }
            if (-not $attrsCenter) {
                $m = [regex]::Match($block, 'mAttrs=\{\((\d+),(\d+)\)\((\d+)x(\d+)\)')
                if ($m.Success -and [int] $m.Groups[3].Value -lt 200) {
                    $attrsCenter = [pscustomobject]@{
                        X = ([int] $m.Groups[1].Value + [int]($m.Groups[3].Value / 2))
                        Y = ([int] $m.Groups[2].Value + [int]($m.Groups[4].Value / 2))
                    }
                }
            }
        }
        Start-Sleep -Seconds 2
    }
    return $attrsCenter
}

# 分屏浮窗是否可见 (宽为 match_parent 的 App overlay 块)。
function Test-SplitVisible {
    param([Parameter(Mandatory = $true)][string] $Device)

    foreach ($block in (Get-AppOverlayBlocks -Device $Device)) {
        if ($block -match 'mAttrs=\{\(\d+,\d+\)\(fillx') { return $true }
    }
    return $false
}

# 扇形菜单窗口是否可见 (App overlay 中的方形菜单: 实测 360x360, 旧版约 196x351)。
# 球=88x88、分屏=屏宽 (fillx)，均不会误命中；优先 frame，回退 mAttrs。
function Test-FanMenuVisible {
    param([Parameter(Mandatory = $true)][string] $Device)

    foreach ($block in (Get-AppOverlayBlocks -Device $Device)) {
        $w = 0; $h = 0
        $f = [regex]::Match($block, 'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if (-not $f.Success) {
            $f = [regex]::Match($block, 'frame=\[Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)\]')
        }
        if ($f.Success) {
            $w = [int] $f.Groups[3].Value - [int] $f.Groups[1].Value
            $h = [int] $f.Groups[4].Value - [int] $f.Groups[2].Value
        } else {
            $m = [regex]::Match($block, 'mAttrs=\{\(\d+,\d+\)\((\d+)x(\d+)\)')
            if ($m.Success) { $w = [int] $m.Groups[1].Value; $h = [int] $m.Groups[2].Value }
        }
        if ($w -ge 150 -and $w -le 480 -and $h -ge 250 -and $h -le 620) { return $true }
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

# 自动完成系统的「共享屏幕」投影授权框 (Android 16: 下一步 -> 选前台应用卡)。
function Complete-ProjectionConsent {
    param([Parameter(Mandatory = $true)][string] $Device)

    # Android 16 (API 36) 模拟器实测流程: 授权框(默认「共享一个应用」) →
    # 「下一步」(右下, ~563,962) → 应用选择器(两个预览卡, 左卡=当前前台应用,
    # ~160,537) → 点左卡即开始投影。旧版 (API 33-35) 的「下拉选整个屏幕」
    # 流程已不存在; 若在旧设备上跑冒烟, 仍回退旧坐标。
    $sdk = ((& $Adb -s $Device shell getprop ro.build.version.sdk 2>$null) -join '').Trim()
    if ($sdk -ge '36') {
        & $Adb -s $Device shell input tap 563 962
        Start-Sleep -Milliseconds 1200
        & $Adb -s $Device shell input tap 160 537
        Start-Sleep -Seconds 2
    }
    else {
        & $Adb -s $Device shell input tap 360 580
        Start-Sleep -Milliseconds 800
        & $Adb -s $Device shell input tap 237 730
        Start-Sleep -Milliseconds 800
        & $Adb -s $Device shell input tap 563 974
        Start-Sleep -Seconds 2
    }
}

# 好参谋冒烟: 冷启动 -> 授权 -> 等悬浮球 -> 单击验证扇形菜单 -> 收起 ->
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
    # MANAGE_EXTERNAL_STORAGE 首次由 deny 切到 allow 时系统会直接杀进程（Android 16 实测），
    # 杀完只会有广播拉起进程，不会启动悬浮球服务 —— 必须再主动冷启动一次。
    Start-App $Device
    Start-Sleep -Seconds 2
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
    # 再点球心(扇形顶点空白区=窗口空白)收起菜单, 等收回动画走完并留出
    # 冷启动下 a11y 绑定预热时间(过短的长按会因感知链未就绪而空转)。
    & $Adb -s $Device shell input tap $ball.X $ball.Y
    Start-Sleep -Seconds 2

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

# 国产 ROM 额外权限提醒: 自启动/后台弹出/锁屏显示等 adb 授不了,
# 必须用户在系统设置手动开启 (见 AGENTS.md §6.1 KeepAliveUtils 口径)。
function Show-VendorPermissionHint {
    param([Parameter(Mandatory = $true)][string] $Device)
    $miui = ((& $Adb -s $Device shell getprop ro.miui.ui.version.name 2>$null) -join '').Trim()
    if ($miui) {
        Write-Host "检测到 MIUI ($miui): 以下权限 adb 无法授予, 请在 设置→应用设置→BetterAIChat2 手动开启:" -ForegroundColor Yellow
        Write-Host '  · 自启动  · 后台弹出界面  · 锁屏显示  · 显示悬浮窗  · 省电策略=无限制' -ForegroundColor Yellow
        Write-Host '  (好参谋保活与投影恢复依赖这些; 不开也能用, 但后台可能被杀)' -ForegroundColor DarkGray
    }
}

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
    Show-VendorPermissionHint -Device $Device

    if ($AttachLog) { Invoke-AppLogcat -Device $Device }
    return $true
}

# ======================== GitLab CI 远程发布 ========================

# 发版闭环: 本机 ci -> 推送 laocanmou + vX.Y.Z tag -> 1.100 Runner 自动构建
# -> 下载签名 APK 到 releases/<tag> -> (可选) 装进 1.100 模拟器。
# 所有 GitLab 访问都走 REST API + 本机 PAT, 不依赖 gitlab 网页会话。

# ---------- CI: 凭据 / API ----------

function Get-GitLabToken {
    if (-not (Test-Path $GitLabTokenFile)) {
        Write-Host "未找到 GitLab PAT 文件: $GitLabTokenFile" -ForegroundColor Red
        Write-Host '请创建 PAT (scope=api) 并写入: GITLAB_PAT=glpat-xxx' -ForegroundColor Yellow
        return $null
    }
    $line = (Get-Content $GitLabTokenFile -ErrorAction SilentlyContinue |
        Where-Object { $_ -match '^\s*GITLAB_PAT\s*=' } | Select-Object -First 1)
    if (-not $line) {
        Write-Host "PAT 文件缺少 GITLAB_PAT= 行: $GitLabTokenFile" -ForegroundColor Red
        return $null
    }
    $token = ($line -split '=', 2)[1].Trim()
    if (-not $token) {
        Write-Host "PAT 文件中的 GITLAB_PAT 为空: $GitLabTokenFile" -ForegroundColor Red
        return $null
    }
    return $token
}

# 调 GitLab REST API; 失败返回 $null (调用方统一按失败处理, 不抛异常)
function Invoke-GitLabApi {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [string] $Method = 'GET',
        [hashtable] $Form
    )

    $token = Get-GitLabToken
    if (-not $token) { return $null }
    $uri = '{0}/api/v4{1}' -f $GitLabHost, $Path
    $headers = @{ 'PRIVATE-TOKEN' = $token }
    try {
        if ($Form) {
            return Invoke-RestMethod -Uri $uri -Method $Method -Headers $headers -Body $Form `
                -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 30
        }
        return Invoke-RestMethod -Uri $uri -Method $Method -Headers $headers -TimeoutSec 30
    }
    catch {
        Write-Host "GitLab API 失败: $Method $Path" -ForegroundColor Red
        Write-Host "  $($_.Exception.Message)" -ForegroundColor DarkGray
        return $null
    }
}

# 取 job 日志尾部: 构建失败时本机直接看关键报错 (作业容器结束即删, 日志是唯一现场)
function Get-GitLabJobTrace {
    param(
        [Parameter(Mandatory = $true)][int] $JobId,
        [int] $Tail = 80
    )
    $token = Get-GitLabToken
    if (-not $token) { return }
    $uri = '{0}/api/v4/projects/{1}/jobs/{2}/trace' -f $GitLabHost, $GitLabProjectId, $JobId
    try {
        $resp = Invoke-WebRequest -Uri $uri -Headers @{ 'PRIVATE-TOKEN' = $token } `
            -UseBasicParsing -TimeoutSec 30
        $lines = @($resp.Content -split "`r?`n" | Where-Object { $_ -ne '' })
        if ($lines.Count -gt $Tail) { $lines = $lines[($lines.Count - $Tail)..($lines.Count - 1)] }
        Write-Host ("---- job #{0} 日志尾部 (最后 {1} 行) ----" -f $JobId, $Tail) -ForegroundColor DarkGray
        $lines | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    }
    catch {
        Write-Host "读取 job #$JobId 日志失败: $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

# ---------- CI: git / 版本 ----------

# 执行 git 并把输出直接打到控制台, 只把成功/失败 (bool) 作为返回值,
# 避免 git 的文本输出污染调用方的 if 判断。
function Invoke-Git {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]] $Arguments)
    & git -C $ProjectDir @Arguments | Write-Host
    return ($LASTEXITCODE -eq 0)
}

# 自动版本号: 取已有最大 vX.Y.Z 的补丁号 +1; 无 tag 从 v1.0.0 起步。
# 调用前应先 git fetch <remote> --tags, 避免和远端已有 tag 撞号。
function Get-NextReleaseVersion {
    $max = $null
    foreach ($t in @(& git -C $ProjectDir tag --list 'v*' 2>$null)) {
        $s = ($t | Out-String).Trim()
        if ($s -notmatch '^v(\d+)\.(\d+)\.(\d+)$') { continue }
        $num = [int]$Matches[1] * 10000 + [int]$Matches[2] * 100 + [int]$Matches[3]
        if ($null -eq $max -or $num -gt $max.Num) {
            $max = [pscustomobject]@{
                Num   = $num
                Major = [int]$Matches[1]
                Minor = [int]$Matches[2]
                Patch = [int]$Matches[3]
            }
        }
    }
    if ($null -eq $max) { return 'v1.0.0' }
    if ($max.Patch -ge 99) { return ('v{0}.{1}.0' -f $max.Major, ($max.Minor + 1)) }
    return ('v{0}.{1}.{2}' -f $max.Major, $max.Minor, ($max.Patch + 1))
}

# ---------- CI: 流水线 ----------

# 按 ref 列流水线 (tag 触发流水线的 ref 就是 tag 名)
function Get-CiPipelinesByRef {
    param([Parameter(Mandatory = $true)][string] $Ref)
    $list = Invoke-GitLabApi -Path ("/projects/{0}/pipelines?ref={1}&per_page=20" -f $GitLabProjectId, [uri]::EscapeDataString($Ref))
    if (-not $list) { return }
    @(@($list) | Where-Object { $_.ref -eq $Ref })
}

# 等 tag push 后新出现的流水线; 排除推送前已存在的 id, 重打 tag 时才不会抓到旧流水线。
function Wait-NewCiPipeline {
    param(
        [Parameter(Mandatory = $true)][string] $Ref,
        [int[]] $ExcludeIds = @(),
        [int] $TimeoutSec = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        foreach ($p in @(Get-CiPipelinesByRef -Ref $Ref)) {
            if ($ExcludeIds -contains [int]$p.id) { continue }
            return $p
        }
    }
    return $null
}

function Wait-CiPipeline {
    param(
        [Parameter(Mandatory = $true)][int] $PipelineId,
        [int] $TimeoutSec = $CiTimeoutSec
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $last = ''
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 10
        $p = Invoke-GitLabApi -Path "/projects/$GitLabProjectId/pipelines/$PipelineId"
        if (-not $p) { continue }
        $status = "$($p.status)"
        if ($status -ne $last) {
            $last = $status
            Write-Host ("  [{0}] pipeline #{1} -> {2}" -f (Get-Date -Format 'HH:mm:ss'), $PipelineId, $status) -ForegroundColor Cyan
        }
        if ($status -in @('success', 'failed', 'canceled', 'skipped')) { return $p }
    }
    Write-Host "等待流水线超时 (${TimeoutSec}s), 稍后用 ci-status 查看。" -ForegroundColor Yellow
    return $null
}

# 打印流水线作业明细 (耗时/状态/失败原因), 失败作业自动附日志尾部
function Show-CiJobs {
    param([Parameter(Mandatory = $true)][int] $PipelineId)

    $jobs = Invoke-GitLabApi -Path "/projects/$GitLabProjectId/pipelines/$PipelineId/jobs?per_page=100"
    if (-not $jobs) { return }
    Write-Host ("作业明细 (pipeline #{0}):" -f $PipelineId) -ForegroundColor Cyan
    foreach ($j in @($jobs)) {
        $dur = if ($j.duration) { [TimeSpan]::FromSeconds([double]$j.duration).ToString('mm\:ss') } else { '-' }
        $color = switch ("$($j.status)") {
            'success' { 'Green' }
            'failed' { 'Red' }
            default { 'Yellow' }
        }
        $extra = if ($j.failure_reason) { " ($($j.failure_reason))" } else { '' }
        Write-Host ("  {0,-8} {1,-9} {2,7}{3}" -f $j.name, $j.status, $dur, $extra) -ForegroundColor $color
    }
    foreach ($j in @($jobs | Where-Object { $_.status -eq 'failed' })) {
        Get-GitLabJobTrace -JobId $j.id -Tail 60
    }
}

function Show-CiStatus {
    param([int] $Limit = 8)

    $pipelines = Invoke-GitLabApi -Path ("/projects/{0}/pipelines?per_page={1}" -f $GitLabProjectId, $Limit)
    if (-not $pipelines) { return }
    Write-Host ("最近 {0} 条流水线 ({1}, 项目 #{2}):" -f $Limit, $GitLabHost, $GitLabProjectId) -ForegroundColor Cyan
    foreach ($p in @($pipelines)) {
        $color = switch ("$($p.status)") {
            'success' { 'Green' }
            'failed' { 'Red' }
            default { 'Yellow' }
        }
        $shortSha = if ($p.sha) { "$($p.sha)".Substring(0, [Math]::Min(8, "$($p.sha)".Length)) } else { '-' }
        Write-Host ("  #{0,-5} {1,-9} {2,-12} {3} {4}" -f $p.id, $p.status, $p.ref, $shortSha, $p.created_at) -ForegroundColor $color
    }
    $latest = @($pipelines)[0]
    if ($latest) {
        Write-Host ''
        Show-CiJobs -PipelineId $latest.id
        Write-Host ("  详情: {0}" -f $latest.web_url) -ForegroundColor DarkGray
    }
}

function Get-LatestSuccessfulPipeline {
    $list = Invoke-GitLabApi -Path ("/projects/{0}/pipelines?status=success&per_page=1" -f $GitLabProjectId)
    if (-not $list) { return $null }
    return @($list)[0]
}

# 下载流水线里所有成功作业的产物到 releases/<tag>/, APK 平铺并校验 SHA256
function Save-CiArtifacts {
    param(
        [Parameter(Mandatory = $true)][string] $Tag,
        [Parameter(Mandatory = $true)][int] $PipelineId
    )

    $token = Get-GitLabToken
    if (-not $token) { return $false }
    $jobs = Invoke-GitLabApi -Path "/projects/$GitLabProjectId/pipelines/$PipelineId/jobs?per_page=100"
    if (-not $jobs) { return $false }

    $dir = Join-Path $ReleaseDir $Tag
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $saved = 0
    foreach ($j in @($jobs)) {
        if ($j.status -ne 'success') { continue }
        if (-not $j.artifacts_file -or -not $j.artifacts_file.filename) { continue }

        $jobDir = Join-Path $dir ('artifacts-' + $j.name)
        if (Test-Path $jobDir) { Remove-Item $jobDir -Recurse -Force }
        New-Item -ItemType Directory -Force -Path $jobDir | Out-Null

        $zip = Join-Path $dir ('artifacts-' + $j.name + '.zip')
        $uri = '{0}/api/v4/projects/{1}/jobs/{2}/artifacts' -f $GitLabHost, $GitLabProjectId, $j.id
        try {
            Invoke-WebRequest -Uri $uri -Headers @{ 'PRIVATE-TOKEN' = $token } `
                -OutFile $zip -UseBasicParsing -TimeoutSec 300
        }
        catch {
            Write-Host "下载作业 $($j.name) 产物失败: $($_.Exception.Message)" -ForegroundColor Red
            continue
        }
        Expand-Archive -Path $zip -DestinationPath $jobDir -Force
        Remove-Item $zip -Force
        Write-Host "  已下载 $($j.name) 产物 -> $jobDir" -ForegroundColor Green
        $saved++
    }

    if ($saved -eq 0) {
        Write-Host '该流水线没有可下载的作业产物。' -ForegroundColor Yellow
        return $false
    }

    # 平铺收口: APK 便于直接安装, mapping.txt 便于崩溃符号化
    $apk = $null
    $buildDir = Join-Path $dir 'artifacts-build'
    if (Test-Path $buildDir) {
        $apk = Get-ChildItem -Path $buildDir -Recurse -Filter '*.apk' -File | Select-Object -First 1
    }
    if (-not $apk) {
        $apk = Get-ChildItem -Path $dir -Recurse -Filter '*.apk' -File | Select-Object -First 1
    }
    if ($apk) {
        $dest = Join-Path $dir ('NovaBAIC-' + $Tag + '.apk')
        if ($apk.FullName -ne $dest) { Copy-Item $apk.FullName $dest -Force }

        $hash = (Get-FileHash -Path $dest -Algorithm SHA256).Hash.ToLower()
        $shaFile = Get-ChildItem -Path $dir -Recurse -Filter '*.sha256' -File | Select-Object -First 1
        if ($shaFile) {
            $expect = ((Get-Content $shaFile.FullName -ErrorAction SilentlyContinue) -join ' ').Trim()
            $expect = ($expect -split '\s+')[0].ToLower()
            if ($expect -eq $hash) {
                Write-Host "  SHA256 校验通过: $hash" -ForegroundColor Green
            }
            elseif ($expect) {
                Write-Host "  SHA256 不一致: 期望 $expect, 实际 $hash" -ForegroundColor Red
            }
        }
        $sizeMb = [math]::Round((Get-Item $dest).Length / 1MB, 1)
        Write-Host "  APK: $dest ($sizeMb MB)" -ForegroundColor Green
    }

    $mapping = Get-ChildItem -Path $dir -Recurse -Filter 'mapping.txt' -File | Select-Object -First 1
    if ($mapping) { Copy-Item $mapping.FullName (Join-Path $dir 'mapping.txt') -Force }
    return $true
}

# 等 tag 流水线跑完并下载产物 (发版 / 重打共用)
function Wait-CiAndFetch {
    param(
        [Parameter(Mandatory = $true)][string] $Tag,
        [int[]] $ExcludeIds = @(),
        [switch] $InstallApk
    )

    $pipeline = Wait-NewCiPipeline -Ref $Tag -ExcludeIds $ExcludeIds
    if (-not $pipeline) {
        Write-Host "未等到 tag $Tag 的流水线, 请到 $GitLabHost 查看。" -ForegroundColor Yellow
        return $false
    }
    Write-Host ("流水线 #{0} 已创建: {1}" -f $pipeline.id, $pipeline.web_url) -ForegroundColor Cyan

    $final = Wait-CiPipeline -PipelineId $pipeline.id
    if (-not $final) { return $false }
    if ($final.status -ne 'success') {
        Write-Host ("流水线 #{0} 结束状态: {1}, 未生成 APK。" -f $pipeline.id, $final.status) -ForegroundColor Red
        Show-CiJobs -PipelineId $pipeline.id
        return $false
    }

    Write-Host '流水线全部通过, 下载产物 ...' -ForegroundColor Green
    $ok = Save-CiArtifacts -Tag $Tag -PipelineId $pipeline.id
    if ($ok -and $InstallApk) { Install-ReleaseApk -Tag $Tag | Out-Null }
    return $ok
}

# ---------- CI: 发版 / 重打 ----------

function Invoke-CiRelease {
    param(
        [string] $Tag,
        [string] $Message,
        [switch] $CommitAll,
        [switch] $InstallApk
    )

    $branch = ((& git -C $ProjectDir rev-parse --abbrev-ref HEAD 2>$null) -join '').Trim()
    if ($branch -ne $GitLabBranch) {
        Write-Host "当前分支为 $branch, 发版必须在 $GitLabBranch 上执行。" -ForegroundColor Red
        return $false
    }

    # 先同步远端 tag, 自动版本号才不会撞上别处刚发的版本
    Invoke-Git fetch $GitLabRemote --tags | Out-Null

    if (-not $Tag) { $Tag = Get-NextReleaseVersion }
    if ($Tag -notmatch '^v\d+\.\d+\.\d+$') {
        Write-Host "版本号必须形如 v1.0.2 (实际: $Tag)" -ForegroundColor Red
        return $false
    }

    $dirty = @(& git -C $ProjectDir status --porcelain 2>$null | Where-Object { $_ -ne '' })
    if ($dirty.Count -gt 0) {
        Write-Host "工作区有 $($dirty.Count) 项未提交改动:" -ForegroundColor Yellow
        $dirty | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
        if (-not $CommitAll) {
            Write-Host '已中止: 确认这些改动都属于本次发版后, 重新执行并允许提交 (ci -Commit / 菜单确认)。' -ForegroundColor Yellow
            return $false
        }
    }

    # 本地编译门禁: 编译不过就别推 tag 白跑一轮 CI
    if (-not (Invoke-KotlinCheck)) { return $false }

    if ($dirty.Count -gt 0) {
        if (-not $Message) { $Message = "chore(release): $Tag" }
        if (-not (Invoke-Git add -A)) { Write-Host 'git add 失败。' -ForegroundColor Red; return $false }
        if (-not (Invoke-Git commit -m $Message)) { Write-Host 'git commit 失败。' -ForegroundColor Red; return $false }
        Write-Host "已提交: $Message" -ForegroundColor Green
    }

    Write-Host "推送分支 $GitLabBranch -> $GitLabRemote ..." -ForegroundColor Cyan
    if (-not (Invoke-Git push $GitLabRemote "HEAD:$GitLabBranch")) {
        Write-Host '推送分支失败。' -ForegroundColor Red
        return $false
    }

    $remoteTag = @(& git -C $ProjectDir ls-remote --tags $GitLabRemote "refs/tags/$Tag" 2>$null | Where-Object { $_ -ne '' })
    if ($remoteTag.Count -gt 0) {
        Write-Host "远端已存在 tag $Tag; 要重打请用 ci-retag -Version $Tag。" -ForegroundColor Red
        return $false
    }

    # 记录推送前该 ref 的流水线 id, 之后只认新出现的那条
    $prevIds = @(Get-CiPipelinesByRef -Ref $Tag | ForEach-Object { [int]$_.id })
    if (-not (Invoke-Git tag $Tag)) { Write-Host "创建 tag $Tag 失败。" -ForegroundColor Red; return $false }
    if (-not (Invoke-Git push $GitLabRemote $Tag)) {
        Write-Host '推送 tag 失败。' -ForegroundColor Red
        return $false
    }
    Write-Host "已推送 tag $Tag, 等待 GitLab 创建流水线 ..." -ForegroundColor Green
    return (Wait-CiAndFetch -Tag $Tag -ExcludeIds $prevIds -InstallApk:$InstallApk)
}

# 重打出错的 tag: 删远端 tag -> 当前 HEAD 重打 -> 重推 -> 等 CI。
# 典型用途: 代码没问题但 CI 环境性失败 (网络/缓存), 或 tag 打错了提交。
function Invoke-CiRetag {
    param([Parameter(Mandatory = $true)][string] $Tag)

    if ($Tag -notmatch '^v\d+\.\d+\.\d+$') {
        Write-Host "版本号必须形如 v1.0.2 (实际: $Tag)" -ForegroundColor Red
        return $false
    }
    $branch = ((& git -C $ProjectDir rev-parse --abbrev-ref HEAD 2>$null) -join '').Trim()
    if ($branch -ne $GitLabBranch) {
        Write-Host "当前分支为 $branch, 重打 tag 必须在 $GitLabBranch 上执行。" -ForegroundColor Red
        return $false
    }
    $dirty = @(& git -C $ProjectDir status --porcelain 2>$null | Where-Object { $_ -ne '' })
    if ($dirty.Count -gt 0) {
        Write-Host '工作区有未提交改动: 重打 tag 只会发布已提交内容, 已中止。' -ForegroundColor Yellow
        return $false
    }

    # tag 必须指向 laocanmou tip (CI 会校验), 先把本地提交推上去
    Write-Host "推送分支 $GitLabBranch ..." -ForegroundColor Cyan
    if (-not (Invoke-Git push $GitLabRemote "HEAD:$GitLabBranch")) {
        Write-Host '推送分支失败。' -ForegroundColor Red
        return $false
    }

    $prevIds = @(Get-CiPipelinesByRef -Ref $Tag | ForEach-Object { [int]$_.id })
    Write-Host "删除远端 tag $Tag ..." -ForegroundColor Cyan
    Invoke-Git push $GitLabRemote ":refs/tags/$Tag" | Out-Null
    Invoke-Git tag -d $Tag | Out-Null
    if (-not (Invoke-Git tag $Tag)) { Write-Host "创建 tag $Tag 失败。" -ForegroundColor Red; return $false }
    if (-not (Invoke-Git push $GitLabRemote $Tag)) {
        Write-Host '推送 tag 失败。' -ForegroundColor Red
        return $false
    }
    Write-Host "已重推 tag $Tag, 等待 GitLab 创建流水线 ..." -ForegroundColor Green
    return (Wait-CiAndFetch -Tag $Tag -ExcludeIds $prevIds)
}

# ---------- CI: 1.100 模拟器 ----------

# 1.100 的模拟器 ADB 只监听容器回环 (5555), 本机经 SSH 本地转发访问。
# 隧道进程 hidden 启动, PID 记录在 %TEMP%\baic2-ci-emu-tunnel.pid 便于手工收尾。
function Initialize-RemoteEmulator {
    param(
        [string] $SshHost = $RemoteEmulatorSshHost,
        [int] $LocalPort = $RemoteEmulatorLocalPort
    )

    $serial = '127.0.0.1:' + $LocalPort
    if (@(Get-AdbDeviceSerials) -contains $serial) {
        $booted = ((& $Adb -s $serial shell getprop sys.boot_completed 2>$null) -join '').Trim()
        if ($booted -eq '1') {
            Write-Host "远程模拟器已在线: $serial" -ForegroundColor Green
            return $serial
        }
    }

    $ssh = Get-Command ssh.exe -ErrorAction SilentlyContinue
    if (-not $ssh) { $ssh = Get-Command ssh -ErrorAction SilentlyContinue }
    if (-not $ssh) {
        Write-Host '未找到 ssh 客户端, 无法建立到 1.100 的模拟器隧道。' -ForegroundColor Red
        return $null
    }

    Write-Host "建立 SSH 隧道: 127.0.0.1:$LocalPort -> $SshHost : 127.0.0.1:5555 ..." -ForegroundColor Cyan
    # BatchMode: 只走密钥不弹密码; ExitOnForwardFailure: 端口被占用时立即失败而不是静默
    $sshArgs = @(
        '-N',
        '-o', 'BatchMode=yes',
        '-o', 'ExitOnForwardFailure=yes',
        '-o', 'ServerAliveInterval=30',
        '-L', "127.0.0.1:${LocalPort}:127.0.0.1:5555",
        $SshHost
    )
    $proc = Start-Process -FilePath $ssh.Source -ArgumentList $sshArgs -WindowStyle Hidden -PassThru
    $pidFile = Join-Path $env:TEMP 'baic2-ci-emu-tunnel.pid'
    Set-Content -Path $pidFile -Value $proc.Id -Encoding ASCII

    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        & $Adb connect $serial 2>$null | Out-Null
        if (@(Get-AdbDeviceSerials) -contains $serial) { break }
        if ($proc.HasExited) { break }
    }
    if (-not (@(Get-AdbDeviceSerials) -contains $serial)) {
        $state = if ($proc.HasExited) { "ssh 已退出, 退出码 $($proc.ExitCode)" } else { 'ssh 仍在运行但端口未通' }
        Write-Host "adb 未能连接 $serial ($state)" -ForegroundColor Red
        Write-Host "请确认 $SshHost 可免密登录, 且 1.100 上的模拟器 sidecar 正在运行。" -ForegroundColor Yellow
        if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        return $null
    }

    & $Adb -s $serial wait-for-device | Out-Null
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        $booted = ((& $Adb -s $serial shell getprop sys.boot_completed 2>$null) -join '').Trim()
        if ($booted -eq '1') {
            Write-Host "远程模拟器启动完成: $serial" -ForegroundColor Green
            return $serial
        }
        Start-Sleep -Seconds 2
    }
    Write-Host '远程模拟器未在时限内开机完成。' -ForegroundColor Red
    return $null
}

# 安装 releases/ 下的签名包到 1.100 模拟器; 未指定 Tag 时取最新一个
function Install-ReleaseApk {
    param([string] $Tag)

    $apk = $null
    if ($Tag) {
        $candidate = Join-Path (Join-Path $ReleaseDir $Tag) ('NovaBAIC-' + $Tag + '.apk')
        if (Test-Path $candidate) { $apk = $candidate }
    }
    if (-not $apk) {
        $latest = Get-ChildItem -Path $ReleaseDir -Recurse -Filter 'NovaBAIC-*.apk' -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($latest) { $apk = $latest.FullName }
    }
    if (-not $apk) {
        Write-Host "releases/ 下没有 APK, 请先执行 ci 或 ci-download。" -ForegroundColor Yellow
        return $false
    }

    $serial = Initialize-RemoteEmulator
    if (-not $serial) { return $false }

    Write-Host ("安装 {0} -> {1}" -f (Split-Path $apk -Leaf), $serial) -ForegroundColor Cyan
    $out = (& $Adb -s $serial install -r $apk 2>&1 | Out-String)
    if ($out -match 'Success') {
        Write-Host '安装成功。' -ForegroundColor Green
        return $true
    }
    # debug 包与 release 包签名不同, 覆盖安装会 UPDATE_INCOMPATIBLE: 卸旧重装
    if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
        Write-Host '签名不一致 (当前装的是 debug 包), 卸载旧包后重装 ...' -ForegroundColor Yellow
        & $Adb -s $serial uninstall $AppId 2>$null | Out-Null
        $out = (& $Adb -s $serial install $apk 2>&1 | Out-String)
        if ($out -match 'Success') {
            Write-Host '安装成功 (已替换旧包)。' -ForegroundColor Green
            return $true
        }
    }
    Write-Host '安装失败:' -ForegroundColor Red
    Write-Host $out
    return $false
}

# 远程模拟器免登录调试: 本地 debug 包 -> 1.100 模拟器, 再用 dev-login 注入测试账号。
# release 包会忽略 debugToken, 所以这里必须走 debug 构建。
function Invoke-CiLogin {
    $serial = Initialize-RemoteEmulator
    if (-not $serial) { return $false }

    if (-not (Invoke-KotlinCheck)) { return $false }
    if (-not (Invoke-Gradle ':app:assembleDebug')) {
        Write-Host 'debug 构建失败, 无法注入免登录。' -ForegroundColor Red
        return $false
    }

    $script = Join-Path $ProjectDir 'dev\dev-login.ps1'
    if (-not (Test-Path $script)) {
        Write-Host "未找到 $script" -ForegroundColor Red
        return $false
    }

    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -Serial $serial -Install
    if ($LASTEXITCODE -eq 0) { return $true }

    # 模拟器上可能还是 release 包 (签名不同导致 install 失败), 卸掉重试一次
    Write-Host 'dev-login 失败: 若当前装的是 release 包, 卸载后重试 ...' -ForegroundColor Yellow
    & $Adb -s $serial uninstall $AppId 2>$null | Out-Null
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -Serial $serial -Install
    return ($LASTEXITCODE -eq 0)
}

# ---------- 子菜单: 远程发布 / CI ----------

function Show-CiMenu {
    while ($true) {
        Write-Header
        Write-Host ' -- 远程发布 / CI (GitLab 1.100: tag 触发构建) --' -ForegroundColor Cyan
        Write-Host ("    {0}  项目 #{1}  分支 {2}" -f $GitLabHost, $GitLabProjectId, $GitLabBranch) -ForegroundColor DarkGray
        Write-Host ' 1) 发版: 提交改动 -> 推送分支 -> 打 tag -> 等 CI -> 下载 APK'
        Write-Host ' 2) 查看流水线状态 (作业耗时; 失败自动打印日志尾部)'
        Write-Host ' 3) 下载最近一次成功构建的产物到 releases/'
        Write-Host ' 4) 重打 tag 并重跑 (删远端 tag -> 重打当前 HEAD -> 推送)'
        Write-Host ' 5) 连接 1.100 模拟器并安装最新 Release APK'
        Write-Host ' 6) 远程模拟器免登录调试 (本地 debug 包 + dev-login)'
        Write-Host ' 0) 返回主菜单'
        Write-Host '========================================='
        $sel = Read-Host '选择 (0 返回)'
        if ($null -eq $sel) { return }  # stdin EOF, 避免空转死循环
        switch ($sel) {
            '1' {
                Invoke-Git fetch $GitLabRemote --tags | Out-Null
                $next = Get-NextReleaseVersion
                $ver = Read-Host "版本号 (回车 = 自动 $next)"
                if ($ver -eq '') { $ver = $next }
                $msg = Read-Host "提交说明 (回车 = chore(release): $ver)"
                if ($msg -eq '') { $msg = "chore(release): $ver" }
                $dirty = @(& git -C $ProjectDir status --porcelain 2>$null | Where-Object { $_ -ne '' })
                if ($dirty.Count -gt 0) {
                    Write-Host "待提交改动 ($($dirty.Count) 项):" -ForegroundColor Yellow
                    $dirty | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
                }
                else {
                    Write-Host '工作区干净, 本次直接对当前提交打 tag。' -ForegroundColor DarkGray
                }
                $confirm = Read-Host "将提交全部改动, 推送 $GitLabBranch 并打 tag $ver, 确认? (y/N)"
                if ($confirm -in @('y', 'Y')) {
                    Invoke-CiRelease -Tag $ver -Message $msg -CommitAll | Out-Null
                }
                else {
                    Write-Host '已取消。' -ForegroundColor Yellow
                }
            }
            '2' { Show-CiStatus }
            '3' {
                $p = Get-LatestSuccessfulPipeline
                if (-not $p) {
                    Write-Host '还没有成功的流水线。' -ForegroundColor Yellow
                }
                else {
                    $tag = "$($p.ref)"
                    if ($tag -notmatch '^v\d+\.\d+\.\d+$') { $tag = "pipeline-$($p.id)" }
                    Save-CiArtifacts -Tag $tag -PipelineId $p.id | Out-Null
                }
            }
            '4' {
                $latestTag = ((& git -C $ProjectDir describe --tags --abbrev=0 2>$null) -join '').Trim()
                $ver = Read-Host "要重打的 tag (回车 = $latestTag)"
                if ($ver -eq '') { $ver = $latestTag }
                $confirm = Read-Host "将删除远端 tag $ver 并重打当前 HEAD, 确认? (y/N)"
                if ($confirm -in @('y', 'Y')) {
                    Invoke-CiRetag -Tag $ver | Out-Null
                }
                else {
                    Write-Host '已取消。' -ForegroundColor Yellow
                }
            }
            '5' { Install-ReleaseApk | Out-Null }
            '6' { Invoke-CiLogin | Out-Null }
            '0' { return }
            default { Write-Host "无效选择: $sel" -ForegroundColor Yellow }
        }
        if ($sel -eq '0') { break }
        Pause-ForMenu
    }
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

    # 汇总可启动目标: 先 BlueStacks 多开实例, 再 AVD
    $targets = @()
    $bstDir = Get-BstDir
    if ($bstDir) {
        foreach ($bst in Get-BstInstances -BstDir $bstDir) {
            $state = if ($bst.Running) { ' (运行中, 仅连接)' } else { '' }
            $labelName = Get-BstLabel -Name $bst.Name -DisplayName $bst.DisplayName
            $targets += [pscustomobject]@{
                Kind    = 'bst'
                Label   = "BlueStacks $labelName$state"
                BstName = $bst.Name
                BstDir  = $bstDir
            }
        }
    }
    foreach ($a in Get-AvdList) {
        $targets += [pscustomobject]@{ Kind = 'avd'; Label = "Android AVD $a"; AvdName = $a }
    }

    if ($targets.Count -eq 0) {
        Write-Host "未找到任何 AVD 或 BlueStacks 实例 (emulator: $Emulator)。" -ForegroundColor Yellow
        return
    }
    if ($targets.Count -eq 1) {
        $t = $targets[0]
        if ($t.Kind -eq 'bst') { Start-BstInstance -BstDir $t.BstDir -Name $t.BstName | Out-Null }
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
        if ($t.Kind -eq 'bst') { Start-BstInstance -BstDir $t.BstDir -Name $t.BstName | Out-Null }
        else { Start-Avd -AvdName $t.AvdName | Out-Null }
    }
    elseif ($sel -ne '0') {
        Write-Host "无效选择: $sel" -ForegroundColor Yellow
    }
}

function Do-KillEmulator {
    $emulators = @((& $Adb devices) -match "`tdevice`$" | ForEach-Object { ($_ -split "`t")[0] } |
        Where-Object { $_ -match '^emulator-\d+|^127\.0\.0\.1:' })
    foreach ($emu in $emulators) {
        # BlueStacks (127.0.0.1:*) 不支持 emu 控制台, 命令静默失败即可, 由下方进程兜底
        & $Adb -s $emu emu kill 2>$null | Out-Null
        Write-Host "已请求关闭模拟器: $emu" -ForegroundColor Green
    }
    # BlueStacks 不响应 adb emu kill, 直接结束其进程兜底
    $bstProcs = @(Get-Process 'HD-Player' -ErrorAction SilentlyContinue)
    foreach ($p in $bstProcs) {
        Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
        Write-Host '已关闭 BlueStacks (HD-Player)。' -ForegroundColor Green
    }
    if (-not $emulators -and $bstProcs.Count -eq 0) {
        Write-Host '未发现运行中的模拟器。' -ForegroundColor Yellow
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
        Write-Host ' 2) 启动模拟器 (BlueStacks / AVD, 可选择)'
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
        Write-Host ' 7) 远程发布 / CI (GitLab 1.100: 提交推送 tag -> 自动构建 -> 下载 APK)'
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
            '7' { Show-CiMenu }
            '0' { break }
            default { Write-Host "无效选择: $choice" -ForegroundColor Yellow }
        }

        if ($choice -eq '0') { break }
        if ($choice -in @('5', '6', '7')) { continue }
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
  smoke                 好参谋冒烟: 授权+冷启动+单击验菜单+长按问屏+等分屏+截图 (tests\tempImg\smoke-*.png)
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

远程发布 / CI (GitLab http://192.168.1.101 项目 #69, 分支 laocanmou):
  ci                    发版闭环: 推送分支 -> 打 tag -> 等 CI -> 下载产物到 releases/<tag>
  ci -Version v1.0.2    指定版本 (缺省 = 已有最大补丁号 +1)
  ci -Commit            允许把工作区全部改动提交进发版提交 (默认脏工作区拒绝)
  ci -ReleaseMessage .. 发版提交说明 (缺省 chore(release): <版本>)
  ci -Install           流水线通过后直接装进 1.100 的模拟器
  ci-status             最近流水线 + 作业耗时; 失败作业自动打印日志尾部
  ci-download           下载最近一次成功流水线的产物到 releases/
  ci-retag -Version ..  删远端 tag -> 重打当前 HEAD -> 重推 (触发重跑)
  ci-install            连接 1.100 模拟器 (SSH 隧道) 并安装 releases/ 最新 APK
  ci-login              本地 debug 包 + dev/dev-login.ps1 注入到 1.100 模拟器

开发环境 DeepSeek 密钥:
  在 local.properties (已被 gitignore) 写入:
    dev.deepseekApiKey=sk-xxx
  debug 构建自动注入, App 首启由 DevAgentSeeder 预置为默认 Agent, 免手动配置。
  Release 构建恒不注入, 生产环境不受影响。

模拟器访问宿主机服务:
  Android 模拟器直接用 10.0.2.2:<port>; USB 真机先执行 .\build-client.ps1 reverse。

部署目标 (交互菜单选择 "一键运行" 时列出):
  真机       USB 连接并开启 USB 调试后自动出现
  BlueStacks  自动从注册表/常见目录发现, 多开实例逐个列出, 未运行自动启动
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
    # 注意: $device 与脚本参数 $Device 是同一变量 (PowerShell 变量名不分大小写)。
    # 不能在这里先赋 $null —— 会把用户显式传入的 -Device 序列号抹掉, 导致
    # Ensure-Device 永远走自动选择 (多设备时静默切到真机)。未指定 -Device 时
    # 其值本就是 '', 无需清空。
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
        'ci' {
            if (-not (Invoke-CiRelease -Tag $Version -Message $ReleaseMessage -CommitAll:$Commit -InstallApk:$Install)) { exit 1 }
        }
        'ci-status' { Show-CiStatus }
        'ci-download' {
            $p = Get-LatestSuccessfulPipeline
            if (-not $p) { Write-Host '还没有成功的流水线。' -ForegroundColor Yellow; exit 1 }
            $tag = "$($p.ref)"
            if ($tag -notmatch '^v\d+\.\d+\.\d+$') { $tag = "pipeline-$($p.id)" }
            if (-not (Save-CiArtifacts -Tag $tag -PipelineId $p.id)) { exit 1 }
        }
        'ci-retag' {
            if (-not $Version) {
                Write-Host 'ci-retag 需要 -Version vX.Y.Z (要重打的 tag)。' -ForegroundColor Red
                exit 1
            }
            if (-not (Invoke-CiRetag -Tag $Version)) { exit 1 }
        }
        'ci-install' {
            if (-not (Install-ReleaseApk -Tag $Version)) { exit 1 }
        }
        'ci-login' {
            if (-not (Invoke-CiLogin)) { exit 1 }
        }
    }
}

# 被 dot-source 时 (单元测试) 不自动进入主流程
if ($MyInvocation.InvocationName -ne '.') {
    Invoke-CommandLine
}
