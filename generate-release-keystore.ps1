param(
    [string]$EnvFile = (Join-Path $PSScriptRoot '.env'),
    [switch]$InitEnv,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

function New-RandomSecret {
    $bytes = New-Object byte[] 48
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

if ($InitEnv) {
    if ((Test-Path -LiteralPath $EnvFile) -and -not $Force) {
        throw "Env file already exists: $EnvFile. Re-run with -Force only if you intentionally want to replace it."
    }

    $releasePassword = New-RandomSecret
    @(
        '# Local release signing config. Do not commit this file.',
        'ANDROID_KEYSTORE_FILE=release-signing/cardvault-release.p12',
        "ANDROID_KEYSTORE_PASSWORD=$releasePassword",
        "ANDROID_KEY_PASSWORD=$releasePassword",
        'ANDROID_KEY_ALIAS=cardvault',
        'ANDROID_DISTINGUISHED_NAME=CN=CardVault,O=CardVault,C=CN'
    ) | Set-Content -LiteralPath $EnvFile -Encoding UTF8

    Write-Host "Generated env file: $EnvFile"
}

function Read-DotEnv {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing env file: $Path. Copy .env.example to .env and fill in your release signing values."
    }

    $values = @{}
    $lines = Get-Content -LiteralPath $Path -Encoding UTF8
    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }

        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
            throw "Invalid .env line: $line"
        }

        $name = $Matches[1]
        $value = $Matches[2].Trim()
        if (
            ($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))
        ) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$name] = $value
    }

    return $values
}

function Get-RequiredValue {
    param(
        [hashtable]$Values,
        [string]$Name
    )

    if (-not $Values.ContainsKey($Name) -or [string]::IsNullOrWhiteSpace($Values[$Name])) {
        throw "Missing required value in .env: $Name"
    }

    $value = [string]$Values[$Name]
    if ($value.StartsWith('CHANGE_ME')) {
        throw "Replace placeholder value in .env: $Name"
    }

    return $value
}

$envValues = Read-DotEnv -Path $EnvFile

$KeystorePassword = Get-RequiredValue -Values $envValues -Name 'ANDROID_KEYSTORE_PASSWORD'
$KeyPassword = Get-RequiredValue -Values $envValues -Name 'ANDROID_KEY_PASSWORD'
$KeyAlias = Get-RequiredValue -Values $envValues -Name 'ANDROID_KEY_ALIAS'
$DistinguishedName = if ($envValues.ContainsKey('ANDROID_DISTINGUISHED_NAME') -and -not [string]::IsNullOrWhiteSpace($envValues['ANDROID_DISTINGUISHED_NAME'])) {
    [string]$envValues['ANDROID_DISTINGUISHED_NAME']
} else {
    'CN=CardVault,O=CardVault,C=CN'
}

if ($KeyPassword -ne $KeystorePassword) {
    throw 'PKCS12 release keystore uses one password. Set ANDROID_KEY_PASSWORD equal to ANDROID_KEYSTORE_PASSWORD.'
}

$OutputDir = Join-Path $PSScriptRoot 'release-signing'
$KeystorePath = Join-Path $OutputDir 'cardvault-release.p12'
$LegacyKeystorePath = Join-Path $OutputDir 'cardvault-release.jks'
$Base64Path = Join-Path $OutputDir 'cardvault-release.base64.txt'

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
    $scoopKeytool = Join-Path $env:USERPROFILE 'scoop\apps\temurin17-jdk\current\bin\keytool.exe'
    if (Test-Path -LiteralPath $scoopKeytool) {
        $keytool = $scoopKeytool
    }
}

if ($null -eq $keytool) {
    throw 'keytool was not found. Install JDK 17 and set JAVA_HOME, or add keytool to PATH.'
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

if ($Force -and (Test-Path -LiteralPath $KeystorePath)) {
    Remove-Item -LiteralPath $KeystorePath -Force
}

if ($Force -and (Test-Path -LiteralPath $LegacyKeystorePath)) {
    Remove-Item -LiteralPath $LegacyKeystorePath -Force
}

if (Test-Path -LiteralPath $LegacyKeystorePath) {
    Write-Host "Migrating existing JKS keystore to PKCS12: $LegacyKeystorePath"
    & $keytool `
        -importkeystore `
        -srckeystore $LegacyKeystorePath `
        -srcstoretype JKS `
        -srcstorepass $KeystorePassword `
        -srckeypass $KeyPassword `
        -srcalias $KeyAlias `
        -destkeystore $KeystorePath `
        -deststoretype PKCS12 `
        -deststorepass $KeystorePassword `
        -destalias $KeyAlias `
        -noprompt
} else {
    & $keytool `
        -genkeypair `
        -v `
        -keystore $KeystorePath `
        -storetype PKCS12 `
        -storepass $KeystorePassword `
        -alias $KeyAlias `
        -keyalg RSA `
        -keysize 4096 `
        -validity 10000 `
        -dname $DistinguishedName
}

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
Write-Host 'ANDROID_KEYSTORE_PASSWORD = value from .env'
Write-Host "ANDROID_KEY_ALIAS = $KeyAlias"
Write-Host 'ANDROID_KEY_PASSWORD = value from .env'
