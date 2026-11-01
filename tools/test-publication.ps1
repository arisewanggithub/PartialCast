param([Parameter(Mandatory = $true)][string]$BuildTools)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $root 'gradlew'
$keytool = [System.IO.Path]::Combine($env:JAVA_HOME, 'bin', 'keytool')
if ($env:OS -eq 'Windows_NT') {
    $gradle += '.bat'
    $keytool += '.exe'
}
function Invoke-TestNative {
    param([string]$Tool, [string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = @(& $Tool @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    return @{ ExitCode = $code; Text = (($lines | ForEach-Object { $_.ToString() }) -join "`n") }
}
function Assert-GradleRejects {
    param([string]$Property, [string]$Message)
    $result = Invoke-TestNative $gradle @(':app:help', $Property, '--no-daemon', '--console=plain', '--quiet')
    if ($result.ExitCode -eq 0 -or $result.Text -notmatch $Message) {
        throw "Gradle did not reject invalid publication configuration: $Property"
    }
}
$signingVariables = @('ANDROID_KEYSTORE_PATH', 'ANDROID_KEY_ALIAS', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_PASSWORD')
foreach ($name in $signingVariables + @('ANDROID_KEYSTORE_BASE64')) {
    if ([Environment]::GetEnvironmentVariable($name)) { throw 'Publication tests must not use production signing configuration' }
}
Assert-GradleRejects '-PrequireReleaseSigning=true' 'Publication requires dedicated PartialCast signing'
Assert-GradleRejects '-PrequireReleaseSigning=invalid' 'must be literal true or false'
Assert-GradleRejects '-PreleaseVersionCode=0' 'releaseVersionCode must be an integer'
Assert-GradleRejects '-PreleaseVersionName=invalid' 'releaseVersionName must be a semantic version'
$temporary = Join-Path ([System.IO.Path]::GetTempPath()) ('partialcast-publication-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary | Out-Null
$keystore = Join-Path $temporary 'fixture.p12'
$certificate = Join-Path $temporary 'fixture.der'
$previousPassword = $env:PARTIALCAST_TEST_PASSWORD
$env:PARTIALCAST_TEST_PASSWORD = [guid]::NewGuid().ToString('N')
$signed = [System.IO.Path]::Combine([string[]]@($root, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk'))
$unsigned = [System.IO.Path]::Combine([string[]]@($root, 'app', 'build', 'outputs', 'apk', 'release', 'app-release-unsigned.apk'))
try {
    # This independently generated short-lived signer is a fixture, never a release asset.
    $generated = Invoke-TestNative $keytool @('-genkeypair', '-keystore', $keystore, '-storetype', 'PKCS12',
        '-alias', 'partialcast-ci-fixture', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '2',
        '-dname', 'CN=PartialCast CI Fixture', '-storepass:env', 'PARTIALCAST_TEST_PASSWORD',
        '-keypass:env', 'PARTIALCAST_TEST_PASSWORD', '-noprompt')
    if ($generated.ExitCode -ne 0) { throw 'Independent signing fixture generation failed' }
    $env:ANDROID_KEYSTORE_PATH = $keystore
    $env:ANDROID_KEY_ALIAS = 'partialcast-ci-fixture'
    $env:ANDROID_KEYSTORE_PASSWORD = $env:PARTIALCAST_TEST_PASSWORD
    $env:ANDROID_KEY_PASSWORD = $env:PARTIALCAST_TEST_PASSWORD
    $built = Invoke-TestNative $gradle @(':app:assembleRelease', '-PrequireReleaseSigning=true',
        '-PreleaseVersionCode=10001', '-PreleaseVersionName=0.1.0', '--no-daemon', '--console=plain', '--quiet')
    if ($built.ExitCode -ne 0) { throw 'Signed optimized fixture assembly failed' }
    $exported = Invoke-TestNative $keytool @('-exportcert', '-keystore', $keystore, '-alias', 'partialcast-ci-fixture',
        '-storepass:env', 'PARTIALCAST_TEST_PASSWORD', '-file', $certificate)
    if ($exported.ExitCode -ne 0) { throw 'Fixture public certificate export failed' }
    $fingerprint = (Get-FileHash -LiteralPath $certificate -Algorithm SHA256).Hash
    & (Join-Path $PSScriptRoot 'verify-apk.ps1') -Apk $signed -BuildTools $BuildTools -Signing Signed `
        -VersionCode 10001 -ExpectedCertificateSha256 $fingerprint
    $rejected = $false
    try {
        & (Join-Path $PSScriptRoot 'verify-apk.ps1') -Apk $signed -BuildTools $BuildTools -Signing Signed `
            -VersionCode 10001 -ExpectedCertificateSha256 ('0' * 64)
    } catch {
        if ($_.Exception.Message -ne 'Permanent release signer mismatch') { throw }
        $rejected = $true
    }
    if (!$rejected) { throw 'Wrong certificate was accepted' }
} finally {
    foreach ($name in $signingVariables) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
    $env:PARTIALCAST_TEST_PASSWORD = $previousPassword
    if (Test-Path -LiteralPath $keystore) { Remove-Item -LiteralPath $keystore }
    if (Test-Path -LiteralPath $certificate) { Remove-Item -LiteralPath $certificate }
    Remove-Item -LiteralPath $temporary
    $restored = Invoke-TestNative $gradle @(':app:assembleRelease', '-PrequireReleaseSigning=false',
        '--no-daemon', '--console=plain', '--quiet')
    if ($restored.ExitCode -ne 0) { throw 'Could not restore unsigned diagnostic release after fixture tests' }
    if (Test-Path -LiteralPath $signed) { Remove-Item -LiteralPath $signed }
}
& (Join-Path $PSScriptRoot 'verify-apk.ps1') -Apk $unsigned -BuildTools $BuildTools
Write-Output 'PASS: signing/version failure gates, independently signed optimized fixture, certificate mismatch, unsigned restoration'
