<#
.SYNOPSIS
  Gera o APK de prova de posse do pacote com.gluview.app para o registro no Android Developer
  Console (verificação de desenvolvedor do Google, em vigor no Brasil desde 30/09/2026).

.DESCRIPTION
  Na página Packages do console, depois de cadastrar o pacote e a impressão digital SHA-256 da chave B,
  o Google entrega um arquivo adi-registration.properties. Ele precisa ir nos assets de um APK assinado
  com a chave B, e esse APK é enviado de volta no console. Este script:
    1. copia o arquivo para app\src\main\assets\ (só durante o build; ele é apagado no fim e fica no
       .gitignore, porque só serve para a prova);
    2. compila o assembleRelease, alinha em 16 KB e assina com B + lineage (tools\chave-b.ps1);
    3. confere que o APK saiu com a chave B e com o arquivo dentro;
    4. deixa o APK em app\build\outputs\registro-google\ e abre a pasta.

  O APK gerado é só para enviar no console. Não instale nem publique.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\prova-registro-google.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\prova-registro-google.ps1 -Arquivo D:\adi-registration.properties
#>
[CmdletBinding()]
param(
    [string]$Arquivo = (Join-Path $env:USERPROFILE "Downloads\adi-registration.properties"),
    [switch]$NaoAbrirPasta
)
# "Continue" pelo mesmo motivo do instalar-dev.ps1: stderr de comando externo não é erro no PowerShell 5.1.
$ErrorActionPreference = "Continue"
. "$PSScriptRoot\chave-b.ps1"
$Root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$bt = Get-ChildItem (Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools") | Sort-Object Name | Select-Object -Last 1
$signer = Join-Path $bt.FullName "apksigner.bat"
$zipalign = Join-Path $bt.FullName "zipalign.exe"
$NomeAsset = "adi-registration.properties"
$destino = Join-Path $Root "app\src\main\assets\$NomeAsset"
$outDir = Join-Path $Root "app\build\outputs\registro-google"

if (-not (Test-Path $Arquivo)) { throw "arquivo do Google não encontrado: $Arquivo (baixe no console, página Packages, ou passe -Arquivo)" }
if ((Get-Item $Arquivo).Length -gt 64KB) { throw "$Arquivo é grande demais para ser o arquivo de registro do Google" }

Copy-Item $Arquivo $destino -Force
try {
    & (Join-Path $Root "gradlew.bat") -p $Root ":app:assembleRelease" --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "build falhou" }
} finally {
    Remove-Item $destino -ErrorAction SilentlyContinue
}

New-Item -ItemType Directory -Force $outDir | Out-Null
$unsigned = Join-Path $Root "app\build\outputs\apk\release\app-release-unsigned.apk"
$aligned = Join-Path $outDir "aligned.apk"
$apk = Join-Path $outDir "GluView-registro-google.apk"
& $zipalign -f -P 16 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign falhou" }

Enable-GluviewKeyBPassword
try {
    if ((Invoke-GluviewSignB $signer $aligned $apk) -ne 0) { throw "apksigner sign falhou" }
} finally {
    Disable-GluviewKeyBPassword
}
Remove-Item $aligned -ErrorAction SilentlyContinue

$certs = & $signer verify --print-certs $apk 2>&1 | Select-String "SHA-256 digest: $GluviewKeyBSha"
if (-not $certs) { throw "o APK não saiu assinado com a chave B" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
try { $temAsset = $zip.Entries | Where-Object { $_.FullName -eq "assets/$NomeAsset" } } finally { $zip.Dispose() }
if (-not $temAsset) { throw "o APK saiu sem assets/$NomeAsset" }

Write-Host "ok: APK de prova assinado com B e com o arquivo do Google: $apk" -ForegroundColor Green
Write-Host "Envie este APK no Android Developer Console (Packages > com.gluview.app > Upload APK)."
if (-not $NaoAbrirPasta) { Start-Process explorer.exe $outDir }
