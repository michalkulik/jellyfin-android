#Requires -Version 5.1
<#
.SYNOPSIS
    Creates a keystore for signing this fork's release builds.

.DESCRIPTION
    Generates a keystore outside of the repository and prints the values that have to be added as
    GitHub Actions repository secrets (Settings -> Secrets and variables -> Actions):

        KEYSTORE           base64 encoded keystore file
        KEYSTORE_PASSWORD  keystore password
        KEY_ALIAS          key alias
        KEY_PASSWORD       key password

    Keep the generated file and the passwords somewhere safe. Without them you cannot publish an
    update that installs over an existing installation.

.PARAMETER OutputDirectory
    Directory for the generated keystore. Defaults to a "signing" folder next to the repository.

.PARAMETER Alias
    Alias of the signing key. Defaults to "jellyfin-fork".

.EXAMPLE
    .\scripts\create-signing-key.ps1
#>
[CmdletBinding()]
param(
    [string]$OutputDirectory,
    [string]$Alias = 'jellyfin-fork'
)

$ErrorActionPreference = 'Stop'

function Get-KeyTool {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/keytool.exe'))) {
        return Join-Path $env:JAVA_HOME 'bin/keytool.exe'
    }

    $command = Get-Command keytool -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    throw "keytool was not found. Install a JDK (Android Studio bundles one) or set JAVA_HOME."
}

function New-RandomPassword {
    # Avoid characters that are awkward to pass through shells and YAML.
    $chars = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    -join (1..24 | ForEach-Object { $chars[(Get-Random -Maximum $chars.Length)] })
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path (Split-Path -Parent $repoRoot) 'signing'
}

if (-not (Test-Path $OutputDirectory)) {
    New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
}

$keystorePath = Join-Path $OutputDirectory 'fork-release.jks'
if (Test-Path $keystorePath) {
    throw "A keystore already exists at $keystorePath. Move it away first if you really want to replace it."
}

$storePassword = New-RandomPassword
$keyPassword = $storePassword
$keytool = Get-KeyTool

Write-Host "Creating keystore at $keystorePath ..."

# keytool writes its progress to stderr, which PowerShell would treat as a terminating error
# because of the ErrorActionPreference set above.
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$keytoolOutput = & $keytool -genkeypair `
    -keystore $keystorePath `
    -storetype PKCS12 `
    -alias $Alias `
    -keyalg RSA `
    -keysize 4096 `
    -validity 10000 `
    -storepass $storePassword `
    -keypass $keyPassword `
    -dname "CN=Jellyfin Android Fork, OU=Development, O=Personal, L=Unknown, ST=Unknown, C=PL" 2>&1
$keytoolExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorActionPreference

if ($keytoolExitCode -ne 0) {
    $keytoolOutput | ForEach-Object { Write-Host $_ }
    throw "keytool failed with exit code $keytoolExitCode."
}

if (-not (Test-Path $keystorePath)) {
    throw "keytool reported success but $keystorePath does not exist."
}

$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))

Write-Host ''
Write-Host 'Add these as repository secrets (Settings -> Secrets and variables -> Actions):'
Write-Host ''
Write-Host "KEYSTORE          = $base64"
Write-Host "KEYSTORE_PASSWORD = $storePassword"
Write-Host "KEY_ALIAS         = $Alias"
Write-Host "KEY_PASSWORD      = $keyPassword"
Write-Host ''
Write-Host "Keystore file: $keystorePath"
Write-Host 'Keep the file and the passwords safe. The keystore is ignored by git and must not be committed.'
