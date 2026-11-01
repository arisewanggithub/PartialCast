param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$BuildTools,
    [ValidateSet('Unsigned', 'Signed')][string]$Signing = 'Unsigned',
    [long]$VersionCode = 1,
    [string]$VersionName = '0.1.0',
    [string]$ExpectedCertificateSha256 = '',
    [string]$DebugApk = '',
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'

function Invoke-AndroidTool {
    param([string]$Name, [string[]]$Arguments)
    $suffix = ''
    if ($env:OS -eq 'Windows_NT') {
        if ($Name -eq 'apksigner') { $suffix = '.bat' } else { $suffix = '.exe' }
    }
    $tool = Join-Path $BuildTools ($Name + $suffix)
    if (!(Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Missing Android tool: $Name" }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = @(& $tool @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    return @{ ExitCode = $code; Text = (($lines | ForEach-Object { $_.ToString() }) -join "`n") }
}

if (!(Test-Path -LiteralPath $Apk -PathType Leaf)) { throw 'APK is missing' }
if ($VersionCode -lt 1 -or $VersionCode -gt 2147483647) { throw 'Invalid expected versionCode' }
if ($Signing -eq 'Signed' -and $ExpectedCertificateSha256 -notmatch '^[a-fA-F0-9]{64}$') {
    throw 'Signed publication requires a pinned permanent certificate SHA256'
}
$badging = Invoke-AndroidTool 'aapt2' @('dump', 'badging', $Apk)
if ($badging.ExitCode -ne 0) { throw 'APK badging failed' }
foreach ($expected in @("name='dev.screencopy.partialcast'", "versionCode='$VersionCode'",
    "versionName='$VersionName'", "minSdkVersion:'30'", "targetSdkVersion:'37'", "application-label:'PartialCast'")) {
    if (!$badging.Text.Contains($expected)) { throw "APK identity/version mismatch: $expected" }
}
$manifest = Invoke-AndroidTool 'aapt2' @('dump', 'xmltree', '--file', 'AndroidManifest.xml', $Apk)
if ($manifest.ExitCode -ne 0) { throw 'APK manifest extraction failed' }
$text = $manifest.Text
if ([regex]::Matches($text, 'E: uses-permission ').Count -ne 3 -or
    $text -match 'android.permission.(INTERNET|ACCESS_LOCAL_NETWORK|RECORD_AUDIO|REQUEST_INSTALL_PACKAGES|SYSTEM_ALERT_WINDOW)') {
    throw 'APK permission boundary changed'
}
if ([regex]::Matches($text, 'E: activity ').Count -ne 1 -or
    [regex]::Matches($text, 'E: service ').Count -ne 1 -or $text -match 'E: (provider|receiver) ') {
    throw 'Unexpected APK components'
}
$activityIndex = $text.IndexOf('E: activity ')
$serviceIndex = $text.IndexOf('E: service ')
if ($serviceIndex -le $activityIndex) { throw 'Unexpected component ordering' }
$activity = $text.Substring($activityIndex, $serviceIndex - $activityIndex)
$service = $text.Substring($serviceIndex)
if ($activity -notmatch 'dev.screencopy.partialcast.HomeActivity' -or $activity -notmatch 'exported.*=true' -or
    $service -notmatch 'dev.screencopy.partialcast.LocalProjectionService' -or $service -notmatch 'exported.*=false' -or
    $service -notmatch 'foregroundServiceType.*0x00000020' -or $text -notmatch 'allowBackup.*=false' -or
    $text -match 'debuggable.*=true') { throw 'Release component/foreground-service/debug policy failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Apk).Path)
try {
    $entries = @($zip.Entries | ForEach-Object { $_.FullName })
    if ($entries -match '^lib/' -or $entries -match '(?i)(scrcpy-server|supervisor|\.apk$|\.so$)') {
        throw 'Forbidden native/bundled executable APK payload'
    }
    foreach ($entry in @('classes.dex', 'resources.arsc', 'assets/NOTICE.txt', 'assets/licenses/Apache-2.0.txt')) {
        if ($entry -notin $entries) { throw "Missing optimized APK resource/notice: $entry" }
    }
} finally { $zip.Dispose() }
$root = Split-Path -Parent $PSScriptRoot
foreach ($report in @('mapping.txt', 'resources.txt')) {
    $path = [System.IO.Path]::Combine([string[]]@($root, 'app', 'build', 'outputs', 'mapping', 'release', $report))
    if (!(Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -eq 0) {
        throw "Missing R8/resource-shrink evidence: $report"
    }
}
if ($DebugApk) {
    if (!(Test-Path -LiteralPath $DebugApk -PathType Leaf) -or
        (Get-Item -LiteralPath $Apk).Length -ge (Get-Item -LiteralPath $DebugApk).Length) {
        throw 'Optimized release must be smaller than the comparison debug APK'
    }
}
$signature = Invoke-AndroidTool 'apksigner' @('verify', '--print-certs', $Apk)
$certificate = ''
if ($Signing -eq 'Unsigned') {
    if ($signature.ExitCode -eq 0 -or $signature.Text -notmatch 'Missing META-INF/MANIFEST.MF') {
        throw 'CI release is not confirmed unsigned'
    }
    if ($OutputDirectory) { throw 'Unsigned APKs cannot be prepared as publication assets' }
} else {
    $matches = [regex]::Matches($signature.Text,
        '(?im)^\s*(?:Signer #[0-9]+|V[0-9.]+ Signer[^:\r\n]*):?\s+certificate SHA-256 digest:\s*([a-fA-F0-9]{64})\s*$')
    $certificates = @($matches | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } | Select-Object -Unique)
    if ($signature.ExitCode -ne 0 -or $certificates.Count -ne 1) { throw 'Release must have one verified signer' }
    $certificate = $certificates[0]
    if ($certificate -ne $ExpectedCertificateSha256.ToLowerInvariant()) { throw 'Permanent release signer mismatch' }
}
$alignment = Invoke-AndroidTool 'zipalign' @('-c', '4', $Apk)
if ($alignment.ExitCode -ne 0) { throw 'APK alignment verification failed' }
$sha = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($OutputDirectory) {
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    Copy-Item -LiteralPath $Apk -Destination (Join-Path $OutputDirectory 'PartialCast.apk')
    "$sha  PartialCast.apk" | Set-Content -LiteralPath (Join-Path $OutputDirectory 'SHA256SUMS') -Encoding ASCII
}
Write-Output "Verified optimized PartialCast: code=$VersionCode version=$VersionName signing=$Signing SHA256=$sha"
if ($certificate) { Write-Output "Pinned certificate SHA256=$certificate" }
