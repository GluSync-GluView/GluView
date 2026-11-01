<#
.SYNOPSIS
  Build de teste assinado com a chave B + lineage e instalado por cabo (plano OTA, seção 6, passo 10).

.DESCRIPTION
  Depois da rotação A→B, um app instalado com B recusa APK assinado com A — e o Run do Android
  Studio e o assembleDebug assinam com A (chave de debug padrão do AGP). Por isso, a partir da
  rotação, todo build de teste instalado nos aparelhos passa por aqui:
    1. assembleRelease sem assinatura (R8, igual ao publicado);
    2. zipalign 16 KB e assinatura com B + lineage (tools\chave-b.ps1; senha do arquivo cifrado de
       tools\guardar-senha-b.ps1 ou pedida escondida);
    3. adb install -r em cada aparelho.

  ATENÇÃO: num aparelho ainda com a chave A, instalar este APK FAZ a rotação daquele aparelho
  (irreversível). Use só em aparelhos já migrados, ou quando a rotação for a intenção.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\instalar-dev.ps1 -Devices 0042632077
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string[]]$Devices,
    [switch]$SkipBuild
)
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\chave-b.ps1"
$Root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$bt = Get-ChildItem (Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools") | Sort-Object Name | Select-Object -Last 1
$signer = Join-Path $bt.FullName "apksigner.bat"
$zipalign = Join-Path $bt.FullName "zipalign.exe"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$outDir = Join-Path $Root "app\build\outputs\apk\release"

if (-not $SkipBuild) {
    & (Join-Path $Root "gradlew.bat") -p $Root ":app:assembleRelease" --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "build falhou" }
}
$unsigned = Join-Path $outDir "app-release-unsigned.apk"
$aligned = Join-Path $outDir "app-release-aligned-dev.apk"
$apk = Join-Path $outDir "app-release-dev-B.apk"
& $zipalign -f -P 16 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign falhou" }

Enable-GluviewKeyBPassword
try {
    if ((Invoke-GluviewSignB $signer $aligned $apk) -ne 0) { throw "apksigner sign falhou" }
} finally {
    Disable-GluviewKeyBPassword
}
$certs = & $signer verify --print-certs $apk 2>&1 | Select-String "SHA-256 digest: $GluviewKeyBSha"
if (-not $certs) { throw "o APK não saiu assinado com a chave B" }
Write-Host "ok: build de teste assinado com B: $apk" -ForegroundColor Green

foreach ($d in $Devices) {
    Write-Host "== $d"
    & $adb -s $d install -r $apk
    if ($LASTEXITCODE -ne 0) { Write-Host "falhou em $d" -ForegroundColor Red }
}
