<#
.SYNOPSIS
  Assina um APK com a chave B e o lineage A->B (rotacao de chave, plano OTA secao 6, passos 5 e 7).

.DESCRIPTION
  Roda no PC de publicacao, offline. Pede a senha da chave B escondida (nunca em arquivo, log ou
  chat) e a passa ao apksigner so por variavel de ambiente deste processo.
    1. Se signing/lineage.bin nao existir, cria o lineage A->B (A = debug.keystore, senha publica
       "android"; B = gluview-release.p12). O lineage nao e segredo e vai para o Git.
    2. Alinha (zipalign 16 KB) e assina so com B, APK Signature Scheme v3, rotacao a partir do
       Android 9 (minSdk de assinatura 28) — variante recomendada do plano.
    3. Confere a assinatura e mostra os certificados.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\assinar-com-b.ps1 -Unsigned app-unsigned.apk -Out app-b.apk
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Unsigned,
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$KeyA = (Join-Path $env:USERPROFILE ".android\debug.keystore"),
    [string]$KeyB = (Join-Path $env:USERPROFILE ".gluview-keys\gluview-release.p12"),
    [string]$AliasB = "gluview-b"
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$bt = Get-ChildItem (Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools") | Sort-Object Name | Select-Object -Last 1
$signer = Join-Path $bt.FullName "apksigner.bat"
$zipalign = Join-Path $bt.FullName "zipalign.exe"
$lineage = Join-Path $Root "signing\lineage.bin"

foreach ($f in $Unsigned, $KeyA, $KeyB) { if (-not (Test-Path $f)) { throw "nao encontrado: $f" } }

$secure = Read-Host "Senha da chave B" -AsSecureString
$env:GLUVIEW_B_PASS = [System.Net.NetworkCredential]::new('', $secure).Password
try {
    if (-not (Test-Path $lineage)) {
        New-Item -ItemType Directory -Force (Split-Path $lineage) | Out-Null
        & $signer rotate --out $lineage `
            --old-signer --ks $KeyA --ks-key-alias androiddebugkey --ks-pass pass:android `
            --new-signer --ks $KeyB --ks-key-alias $AliasB --ks-pass env:GLUVIEW_B_PASS
        if ($LASTEXITCODE -ne 0) { throw "apksigner rotate falhou" }
        Write-Host "ok: lineage A->B criado em $lineage" -ForegroundColor Green
    } else {
        Write-Host "ok: usando o lineage existente $lineage" -ForegroundColor Green
    }

    $aligned = "$Out.aligned.apk"
    & $zipalign -f -P 16 4 $Unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw "zipalign falhou" }

    & $signer sign --rotation-min-sdk-version 28 --min-sdk-version 28 `
        --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true `
        --ks $KeyB --ks-key-alias $AliasB --ks-pass env:GLUVIEW_B_PASS `
        --lineage $lineage --debuggable-apk-permitted false --out $Out $aligned
    if ($LASTEXITCODE -ne 0) { throw "apksigner sign falhou" }
    Remove-Item $aligned -Force

    & $signer verify -v --print-certs --min-sdk-version 28 $Out | Select-String "Verifies|Verified using v3|SHA-256 digest|Number of signers"
    Write-Host "ok: assinado com B + lineage: $Out" -ForegroundColor Green
} finally {
    Remove-Item Env:GLUVIEW_B_PASS -ErrorAction SilentlyContinue
}
