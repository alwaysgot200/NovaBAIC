param(
    [string]$Dev = "449c98301422",
    [string]$Out = "",
    [string]$Filter = ""
)
if (-not $Out) {
    $repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    $Out = Join-Path $repoRoot "tests\temp\session_timing.log"
}
# 原始日志落盘（UTF-8，不丢行）：adb logcat 直写文件，进程结束前一直追加。
# 用途：真机复测取证——主日志环形缓冲在 MIUI 上只有 ~600KB（不足 2 分钟），
# 只有把从「测试开始」起的日志全量落盘，事后才能按 auto_timing: 前缀归因。
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "adb"
$args = "-s $Dev logcat -v threadtime"
if ($Filter) { $args += " $Filter" }
$psi.Arguments = $args
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$p = [System.Diagnostics.Process]::Start($psi)
$writer = New-Object System.IO.StreamWriter($Out, $false, [System.Text.UTF8Encoding]::new($false))
try {
    while (-not $p.HasExited) {
        $line = $p.StandardOutput.ReadLine()
        if ($null -eq $line) { break }
        $writer.WriteLine($line)
        $writer.Flush()
    }
} finally {
    $writer.Close()
    if (-not $p.HasExited) { $p.Kill() }
}
Write-Output "logcat capture finished -> $Out"