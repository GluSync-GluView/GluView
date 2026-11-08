<#
.SYNOPSIS
  Laboratório Wear OS 100% virtual: sobe um celular (Google Play) e um relógio Wear OS no emulador,
  liga os dois e instala o GluView. Plano: Projeto DM1/GluView/14_wearos_plano_execucao_emulador.md (Fase 0).

.DESCRIPTION
  AVDs (criados na Fase 0, 07/11/2026):
    GluViewPhonePlay  celular Pixel 7, Android 15 (API 35) com Play Store  -> porta 5560
    GluViewWatchL     Wear OS 5.1 (API 35), redondo grande (~Galaxy Watch 6/7) -> porta 5562
    GluViewWatchS     Wear OS 4 (API 33), redondo pequeno                       -> porta 5562
  Portas fora da 5554/5556 de propósito: outras sessões usam o GluViewHomolog na 5554.

  Pareamento (uma vez por AVD; o snapshot guarda):
    1. No celular, entrar com uma conta Google de TESTE no Play Store e instalar "Wear OS by Google".
    2. Rodar este script (ele faz o `adb forward tcp:5601` que o app usa para achar o emulador).
    3. No app Wear OS do celular: parear -> "Parear com emulador". Ou usar o Pairing Assistant do
       Android Studio (Tools > Device Manager > ... > Pair Wearable).
  Plano B se o pareamento falhar: o relógio busca a glicemia sozinho pelo GluSync local (Fase 7).

.PARAMETER Watch
  L (padrão) ou S.
.PARAMETER GluSyncPort
  Porta do GluSync local no PC (padrão 5000). O celular e o relógio acessam pelo `adb reverse`
  em http://localhost:<porta>; sem reverse, use http://10.0.2.2:<porta>.
.PARAMETER Install
  Instala o APK debug do celular (app/build/outputs/apk/debug) e, quando existir, o do relógio
  (wear/build/outputs/apk/debug). Não compila: rode o Gradle antes.
.PARAMETER Stop
  Desliga os dois emuladores.

.EXAMPLE
  .\tools\wear-lab.ps1
  .\tools\wear-lab.ps1 -Watch S -Install
  .\tools\wear-lab.ps1 -Stop
#>
param(
    [ValidateSet('L', 'S')] [string] $Watch = 'L',
    [int] $GluSyncPort = 5000,
    [switch] $Install,
    [switch] $Stop
)

$ErrorActionPreference = 'Stop'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$phone = 'emulator-5560'
$watchSerial = 'emulator-5562'
$watchAvd = if ($Watch -eq 'L') { 'GluViewWatchL' } else { 'GluViewWatchS' }
$repo = Split-Path $PSScriptRoot -Parent

function Invoke-Adb {
    # adb escreve erros no stderr; com ErrorActionPreference=Stop o PowerShell 5.1 trataria isso
    # como exceção. Aqui o erro vira só saída vazia.
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $adb @args 2>$null } finally { $ErrorActionPreference = $old }
}

function Test-Booted([string] $serial, [string] $avd) {
    if ((Invoke-Adb -s $serial get-state) -ne 'device') { return $false }
    $name = (Invoke-Adb -s $serial emu avd name | Select-Object -First 1) -as [string]
    if ($name -and $name.Trim() -ne $avd) { throw "$serial está ocupado por outro AVD ($($name.Trim()))" }
    return ((Invoke-Adb -s $serial shell getprop sys.boot_completed) -as [string]).Trim() -eq '1'
}

function Start-Avd([string] $avd, [int] $port, [string] $serial) {
    if (Test-Booted $serial $avd) { Write-Host "$avd já está no ar ($serial)"; return }
    Write-Host "Subindo $avd na porta $port..."
    Start-Process -FilePath $emulator -ArgumentList @('-avd', $avd, '-port', $port, '-no-boot-anim') -WindowStyle Minimized | Out-Null
    $deadline = (Get-Date).AddMinutes(5)
    while (-not (Test-Booted $serial $avd)) {
        if ((Get-Date) -gt $deadline) { throw "$avd não terminou o boot em 5 min" }
        Start-Sleep -Seconds 3
    }
    Write-Host "$avd pronto ($serial)"
}

if ($Stop) {
    foreach ($s in @($phone, $watchSerial)) { Invoke-Adb -s $s emu kill | Out-Null }
    Write-Host 'Emuladores desligados.'
    return
}

& $adb start-server | Out-Null
Start-Avd 'GluViewPhonePlay' 5560 $phone
Start-Avd $watchAvd 5562 $watchSerial

# Ponte celular <-> relógio usada pelo app "Wear OS by Google" para achar o emulador.
Invoke-Adb -s $phone forward tcp:5601 tcp:5601 | Out-Null
# GluSync local acessível como localhost nos dois aparelhos.
foreach ($s in @($phone, $watchSerial)) { Invoke-Adb -s $s reverse "tcp:$GluSyncPort" "tcp:$GluSyncPort" | Out-Null }

if ($Install) {
    $phoneApk = Get-ChildItem (Join-Path $repo 'app\build\outputs\apk\debug') -Filter *.apk -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($phoneApk) { & $adb -s $phone install -r $phoneApk.FullName } else { Write-Warning 'APK debug do celular não encontrado (rode :app:assembleDebug).' }
    $wearApk = Get-ChildItem (Join-Path $repo 'wear\build\outputs\apk\debug') -Filter *.apk -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($wearApk) { & $adb -s $watchSerial install -r $wearApk.FullName } else { Write-Host 'Sem APK do relógio ainda (módulo :wear chega na Fase 5).' }
}

$wearApp = Invoke-Adb -s $phone shell pm list packages com.google.android.wearable.app
Write-Host ''
Write-Host "Celular: $phone | Relógio ($watchAvd): $watchSerial | GluSync: http://localhost:$GluSyncPort"
if (-not $wearApp) {
    Write-Host 'Pareamento pendente: no celular, entre no Play Store com a conta de TESTE e instale "Wear OS by Google".'
}
