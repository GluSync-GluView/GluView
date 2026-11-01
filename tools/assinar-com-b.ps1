<#
.SYNOPSIS
  Assina um APK com a chave B e o lineage A->B (rotacao de chave, plano OTA secao 6, passos 5 e 7).

.DESCRIPTION
  Roda no PC de publicacao, offline. A senha da chave B vem do arquivo cifrado de
  tools\guardar-senha-b.ps1 ou e pedida escondida (ver tools\chave-b.ps1).
    1. Se signing/lineage.bin nao existir, cria o lineage A->B (A = debug.keystore, senha publica
       "android"). O lineage nao e segredo e vai para o Git.
    2. Alinha (zipalign 16 KB) e assina so com B, APK Signature Scheme v3, rotacao a partir do
       Android 9 (minSdk 28) — variante recomendada do plano.
    3. Confere a assinatura e mostra os certificados.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\assinar-com-b.ps1 -Unsigned app-unsigned.apk -Out app-b.apk
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Unsigned,
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$KeyA = (Join-Path $env:USERPROFILE ".android\debug.keystore")
)
# "Continue": no PowerShell 5.1, com "Stop", linha que zipalign/apksigner/adb escrevam no stderr vira
# erro fatal mesmo quando deu certo. Cada comando externo é julgado pelo $LASTEXITCODE.
$ErrorActionPreference = "Continue"
. "$PSScriptRoot\chave-b.ps1"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$bt = Get-ChildItem (Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools") | Sort-Object Name | Select-Object -Last 1
$signer = Join-Path $bt.FullName "apksigner.bat"
$zipalign = Join-Path $bt.FullName "zipalign.exe"

foreach ($f in $Unsigned, $KeyA, $GluviewKeyB) { if (-not (Test-Path $f)) { throw "nao encontrado: $f" } }

Enable-GluviewKeyBPassword
try {
    if (-not (Test-Path $GluviewLineage)) {
        New-Item -ItemType Directory -Force (Split-Path $GluviewLineage) | Out-Null
        & $signer rotate --out $GluviewLineage `
            --old-signer --ks $KeyA --ks-key-alias androiddebugkey --ks-pass pass:android `
            --new-signer --ks $GluviewKeyB --ks-key-alias $GluviewKeyBAlias --ks-pass env:GLUVIEW_B_PASS
        if ($LASTEXITCODE -ne 0) { throw "apksigner rotate falhou" }
        Write-Host "ok: lineage A->B criado em $GluviewLineage" -ForegroundColor Green
    } else {
        Write-Host "ok: usando o lineage existente $GluviewLineage" -ForegroundColor Green
    }

    $aligned = "$Out.aligned.apk"
    & $zipalign -f -P 16 4 $Unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw "zipalign falhou" }
    if ((Invoke-GluviewSignB $signer $aligned $Out) -ne 0) { throw "apksigner sign falhou" }
    Remove-Item $aligned -Force

    & $signer verify -v --print-certs --min-sdk-version 28 $Out | Select-String "Verifies|Verified using v3|SHA-256 digest|Number of signers"
    Write-Host "ok: assinado com B + lineage: $Out" -ForegroundColor Green
} finally {
    Disable-GluviewKeyBPassword
}
