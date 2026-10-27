param(
    [string]$Pkg = "com.verlintas.baic2",
    [string]$Dev = "449c98301422",
    [string]$OutDir = ""
)
if (-not $OutDir) {
    $repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    $OutDir = Join-Path $repoRoot "tests\temp"
}

function Pull-Binary([string]$remote, [string]$local) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "adb"
    $psi.Arguments = "-s $Dev exec-out run-as $Pkg cat $remote"
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $p = [System.Diagnostics.Process]::Start($psi)
    $fs = [System.IO.File]::Create($local)
    try {
        $p.StandardOutput.BaseStream.CopyTo($fs)
    } finally {
        $fs.Close()
    }
    $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    if ($err) { Write-Output "stderr($remote): $err" }
    Write-Output ("pulled {0} -> {1} ({2} bytes)" -f $remote, $local, (Get-Item $local).Length)
}

foreach ($name in @("advisor.db", "advisor.db-wal", "baic2.db", "baic2.db-wal")) {
    Pull-Binary "databases/$name" (Join-Path $OutDir $name)
}
