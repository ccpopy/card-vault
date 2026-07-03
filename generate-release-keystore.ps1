param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

# Edit these values before running this script.
# Keep the generated keystore and passwords private. They are required for future app updates.
$KeystorePassword = 'CHANGE_ME_KEYSTORE_PASSWORD'
$KeyPassword = 'CHANGE_ME_KEY_PASSWORD'
$KeyAlias = 'cardvault'
$DistinguishedName = 'CN=CardVault,O=CardVault,C=CN'

$OutputDir = Join-Path $PSScriptRoot 'release-signing'
$KeystorePath = Join-Path $OutputDir 'cardvault-release.jks'
$Base64Path = Join-Path $OutputDir 'cardvault-release.base64.txt'

if (
    $KeystorePassword -eq 'CHANGE_ME_KEYSTORE_PASSWORD' -or
    $KeyPassword -eq 'CHANGE_ME_KEY_PASSWORD'
) {
    throw 'Edit generate-release-keystore.ps1 first: replace the placeholder passwords at the top of the file.'
}

if ((Test-Path -LiteralPath $KeystorePath) -and -not $Force) {
    throw "Keystore already exists: $KeystorePath. Re-run with -Force only if you intentionally want to replace it before any public release."
}

$keytool = $null
if ($env:JAVA_HOME) {
    $javaHomeKeytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    if (Test-Path -LiteralPath $javaHomeKeytool) {
        $keytool = $javaHomeKeytool
    }
}

if ($null -eq $keytool) {
    $keytoolCommand = Get-Command keytool -ErrorAction SilentlyContinue
    if ($null -ne $keytoolCommand) {
        $keytool = $keytoolCommand.Source
    }
}

if ($null -eq $keytool) {
    throw 'keytool was not found. Install JDK 17 and set JAVA_HOME, or add keytool to PATH.'
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

if ((Test-Path -LiteralPath $KeystorePath) -and $Force) {
    Remove-Item -LiteralPath $KeystorePath -Force
}

& $keytool `
    -genkeypair `
    -v `
    -keystore $KeystorePath `
    -storetype PKCS12 `
    -storepass $KeystorePassword `
    -keypass $KeyPassword `
    -alias $KeyAlias `
    -keyalg RSA `
    -keysize 4096 `
    -validity 10000 `
    -dname $DistinguishedName

if ($LASTEXITCODE -ne 0) {
    throw "keytool failed with exit code $LASTEXITCODE."
}

[Convert]::ToBase64String([IO.File]::ReadAllBytes($KeystorePath)) |
    Set-Content -LiteralPath $Base64Path -Encoding ASCII

Write-Host "Generated keystore: $KeystorePath"
Write-Host "Generated base64:   $Base64Path"
Write-Host ''
Write-Host 'Configure these GitHub Actions secrets:'
Write-Host "ANDROID_KEYSTORE_BASE64 = contents of $Base64Path"
Write-Host "ANDROID_KEYSTORE_PASSWORD = $KeystorePassword"
Write-Host "ANDROID_KEY_ALIAS = $KeyAlias"
Write-Host "ANDROID_KEY_PASSWORD = $KeyPassword"
