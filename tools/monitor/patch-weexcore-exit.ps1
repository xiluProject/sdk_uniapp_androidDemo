param(
    [Parameter(Mandatory = $true)][string]$Aar,
    [Parameter(Mandatory = $true)][string]$Entry,    # so path inside the AAR, e.g. jni/arm64-v8a/libweexcore.so
    [Parameter(Mandatory = $true)][string]$Patches,  # e.g. "1306E8:94054022:D503201F,130934:94053F97:D503201F"
    [string]$JniLibsDir = ""
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

if ($Entry -notmatch '^jni/([^/]+)/') { throw "bad entry (want jni/<abi>/libweexcore.so): $Entry" }
$Abi = $Matches[1]

$patchList = @()
foreach ($p in $Patches.Split(',')) {
    $parts = @($p.Trim().Split(':'))
    if ($parts.Count -ne 3) { throw "bad patch spec '$p' (want OffsetHex:ExpectHex:NopHex)" }
    $patchList += [pscustomobject]@{
        Off    = [Convert]::ToInt32($parts[0].Trim(), 16)
        Expect = [uint32][Convert]::ToUInt32($parts[1].Trim(), 16)
        Nop    = [uint32][Convert]::ToUInt32($parts[2].Trim(), 16)
    }
}

$work = Join-Path $env:TEMP ("weexfix_" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $work | Out-Null
$zip = [System.IO.Compression.ZipFile]::OpenRead($Aar)
$e = $zip.Entries | Where-Object { $_.FullName -eq $Entry } | Select-Object -First 1
if (-not $e) { $zip.Dispose(); throw "entry not found: $Entry" }
$rawPath = Join-Path $work "libweexcore.so"
[System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $rawPath, $true)
$zip.Dispose()

$b = [System.IO.File]::ReadAllBytes($rawPath)
$changed = $false
foreach ($p in $patchList) {
    $cur = [System.BitConverter]::ToUInt32($b, [int]$p.Off)
    if ($cur -eq [uint32]$p.Nop) { Write-Host ("{0} +0x{1:X}: already 0x{2:X8}, skip" -f $Abi, $p.Off, $cur); continue }
    if ($cur -ne [uint32]$p.Expect) {
        throw ("{0} +0x{1:X}: unexpected instruction 0x{2:X8} (expect 0x{3:X8}) -- SDK changed, update patch spec" -f $Abi, $p.Off, $cur, $p.Expect)
    }
    $nb = [System.BitConverter]::GetBytes([uint32]$p.Nop)
    for ($i = 0; $i -lt 4; $i++) { $b[[int]($p.Off + $i)] = $nb[$i] }
    $changed = $true
    Write-Host ("{0} +0x{1:X}: 0x{2:X8} -> 0x{3:X8}" -f $Abi, $p.Off, $cur, $p.Nop)
}

if ($changed) {
    $staged = Join-Path $work $Entry
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $staged) | Out-Null
    [System.IO.File]::WriteAllBytes($staged, $b)
    $jar = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\jar.exe' } else { 'jar' }
    Push-Location $work
    try { & $jar uf $Aar $Entry | Out-Null } finally { Pop-Location }
}

$zip2 = [System.IO.Compression.ZipFile]::OpenRead($Aar)
$e2 = $zip2.Entries | Where-Object { $_.FullName -eq $Entry } | Select-Object -First 1
if (-not $e2) { $zip2.Dispose(); throw "entry lost after update: $Entry" }
$ms = New-Object System.IO.MemoryStream; $s = $e2.Open(); $s.CopyTo($ms); $s.Close(); $zip2.Dispose()
$a2 = $ms.ToArray(); $ms.Dispose()
foreach ($p in $patchList) {
    $now = [System.BitConverter]::ToUInt32($a2, [int]$p.Off)
    if ($now -ne [uint32]$p.Nop) { throw ("AAR verify failed at {0} +0x{1:X}: 0x{2:X8}" -f $Abi, $p.Off, $now) }
}
Write-Host ("AAR verify OK: {0} patch(es) in {1}" -f $patchList.Count, $Entry)

if ($JniLibsDir) {
    $dstDir = Join-Path $JniLibsDir $Abi
    New-Item -ItemType Directory -Force -Path $dstDir | Out-Null
    $dstSo = Join-Path $dstDir 'libweexcore.so'
    [System.IO.File]::WriteAllBytes($dstSo, $a2)
    $chkAll = [System.IO.File]::ReadAllBytes($dstSo)
    foreach ($p in $patchList) {
        $chk = [System.BitConverter]::ToUInt32($chkAll, [int]$p.Off)
        if ($chk -ne [uint32]$p.Nop) { throw ("jniLibs verify failed at {0} +0x{1:X}" -f $Abi, $p.Off) }
    }
    Write-Host ("jniLibs verify OK: {0}" -f $dstSo)
}
