<#
.SYNOPSIS
  Laboratório Wear OS 100% virtual: sobe um celular (Google Play) e um relógio Wear OS no emulador,
  liga os dois e instala o GluView. Plano: Projeto DM1/GluView/14_wearos_plano_execucao_emulador.md (Fase 0).

.DESCRIPTION
  AVDs (criados na Fase 0, 07/11/2026):
    GluViewPhonePlay  celular Pixel 7, Android 15 (API 35) com Play Store  -> porta 5560
    GluViewWatchL     Wear OS 5.1 (API 35), redondo grande (~Galaxy Watch 6/7) -> porta 5562
    GluViewWatch6     Wear OS 6 (API 36), redondo grande — o único com Watch Face Push (plano 16)
    GluViewWatchS     Wear OS 4 (API 33), redondo pequeno                       -> porta 5562
  Portas fora da 5554/5556 de propósito: outras sessões usam o GluViewHomolog na 5554.

  Pareamento (-Pair), validado em 08/11/2026. Pré-requisito: conta Google de TESTE logada no celular
  e app "Google Pixel Watch" (com.google.android.apps.wear.companion) instalado; o antigo
  "Wear OS by Google" não instala no Android 15. É a mesma sequência do Pairing Assistant do
  Android Studio (lida das classes com.android.tools.idea.wearpairing):
    1. PC:5601 -> celular:5601 (adb forward) e relógio:5601 -> PC:5601 (adb reverse no RELÓGIO).
       NUNCA o contrário (forward no relógio + reverse no celular): isso travou o adb inteiro.
    2. Relógio: broadcast com.google.android.gms.wearable.EMULATOR operation=refresh-emulator-connection.
    3. Celular: am start .../.EmulatorActivity com o nome do AVD e o id do nó Wearable do relógio.
  A tela final do app Pixel Watch costuma terminar em "FAILURE NOT FOUND" ("watch not yet matched"),
  mas a conexão Wearable fica de pé: o script confere em "Reachable Nodes" dos dois lados.
  Se o Play Services do relógio estiver corrompido (WearableService "not running" e crash com
  ArrayIndexOutOfBoundsException em ZipUtils), use -FixWatchGms: remove a atualização e reinicia.

.PARAMETER Watch
  L (padrão), S ou 6 (Wear OS 6, para o Watch Face Push).
.PARAMETER GluSyncPort
  Porta do GluSync local no PC (padrão 5000). O celular e o relógio acessam pelo `adb reverse`
  em http://localhost:<porta>; sem reverse, use http://10.0.2.2:<porta>.
.PARAMETER Install
  Instala o APK debug do celular (app/build/outputs/apk/debug) e, quando existir, o do relógio
  (wear/build/outputs/apk/debug). Não compila: rode o Gradle antes.
.PARAMETER Pair
  Pareia celular e relógio (sequência acima) e confere a conexão.
.PARAMETER FixWatchGms
  Remove a atualização do Play Services do relógio (volta à versão da imagem) e reinicia o relógio.
.PARAMETER Stop
  Desliga os dois emuladores.

.EXAMPLE
  .\tools\wear-lab.ps1
  .\tools\wear-lab.ps1 -Watch S -Install
  .\tools\wear-lab.ps1 -Pair
  .\tools\wear-lab.ps1 -Stop
#>
param(
    [ValidateSet('L', 'S', '6')] [string] $Watch = 'L',
    [int] $GluSyncPort = 5000,
    [switch] $Install,
    [switch] $Pair,
    [switch] $FixWatchGms,
    [switch] $Stop
)

$ErrorActionPreference = 'Stop'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$phone = 'emulator-5560'
$watchSerial = 'emulator-5562'
$watchAvd = switch ($Watch) { 'L' { 'GluViewWatchL' } 'S' { 'GluViewWatchS' } '6' { 'GluViewWatch6' } }
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

# GluSync local acessível como localhost nos dois aparelhos.
foreach ($s in @($phone, $watchSerial)) { Invoke-Adb -s $s reverse "tcp:$GluSyncPort" "tcp:$GluSyncPort" | Out-Null }

if ($Install) {
    $phoneApk = Get-ChildItem (Join-Path $repo 'app\build\outputs\apk\debug') -Filter *.apk -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($phoneApk) { & $adb -s $phone install -r $phoneApk.FullName } else { Write-Warning 'APK debug do celular não encontrado (rode :app:assembleDebug).' }
    $wearApk = Get-ChildItem (Join-Path $repo 'wear\build\outputs\apk\debug') -Filter *.apk -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($wearApk) { & $adb -s $watchSerial install -r $wearApk.FullName } else { Write-Host 'Sem APK do relógio ainda (módulo :wear chega na Fase 5).' }
}

function Get-WatchNodeId {
    $line = Invoke-Adb -s $watchSerial shell 'dumpsys activity service WearableService' | Select-String "localNode: NodeInternal\{id='([0-9a-f]+)'" | Select-Object -First 1
    if ($line) { return $line.Matches[0].Groups[1].Value }
    return $null
}

function Test-Paired {
    # Os DOIS lados precisam se ver como vizinhos diretos (hops 1, isNearby true). Só o lado do
    # celular não basta: ele pode guardar a conexão de um relógio anterior na mesma porta (08/11).
    $phoneOut = Invoke-Adb -s $phone shell 'dumpsys activity service WearableService' | Out-String
    $watchOut = Invoke-Adb -s $watchSerial shell 'dumpsys activity service WearableService' | Out-String
    return ($phoneOut -match "$watchAvd\s*:\s*[0-9a-f]+\s*:\s*1\s*:\s*true\s*:\s*true") -and
           ($watchOut -match "GluViewPhonePlay\s*:\s*[0-9a-f]+\s*:\s*1\s*:\s*true")
}

if ($FixWatchGms) {
    Write-Host 'Removendo a atualização do Play Services do relógio e reiniciando...'
    Invoke-Adb -s $watchSerial shell cmd package uninstall-system-updates com.google.android.gms | Out-Null
    Invoke-Adb -s $watchSerial reboot | Out-Null
    Start-Sleep -Seconds 15
    $deadline = (Get-Date).AddMinutes(5)
    while (((Invoke-Adb -s $watchSerial shell getprop sys.boot_completed) -as [string]).Trim() -ne '1') {
        if ((Get-Date) -gt $deadline) { throw 'Relógio não voltou em 5 min' }
        Start-Sleep -Seconds 3
    }
    Start-Sleep -Seconds 30
}

if ($Pair) {
    if (Test-Paired) { Write-Host 'Já pareados.' }
    else {
        $companion = Invoke-Adb -s $phone shell pm list packages com.google.android.apps.wear.companion
        if (-not $companion) { throw 'Instale o app "Google Pixel Watch" no celular (conta de teste no Play Store).' }
        $nodeId = $null
        for ($i = 0; $i -lt 12 -and -not $nodeId; $i++) {
            Invoke-Adb -s $watchSerial shell am broadcast -a com.google.android.gms.INITIALIZE | Out-Null
            Start-Sleep -Seconds 5
            $nodeId = Get-WatchNodeId
        }
        if (-not $nodeId) { throw 'WearableService do relógio não subiu. Tente -FixWatchGms.' }
        Invoke-Adb -s $phone forward tcp:5601 tcp:5601 | Out-Null
        Invoke-Adb -s $watchSerial reverse tcp:5601 tcp:5601 | Out-Null
        Invoke-Adb -s $watchSerial shell am broadcast -a com.google.android.gms.wearable.EMULATOR --es operation refresh-emulator-connection | Out-Null
        Invoke-Adb -s $phone shell am force-stop com.google.android.apps.wear.companion | Out-Null
        Invoke-Adb -s $phone shell am start -n com.google.android.apps.wear.companion/.EmulatorActivity --es emulator-name "`"$watchAvd`"" --es emulator-id "`"$nodeId`"" | Out-Null
        Write-Host "Pareando com $watchAvd (nó $nodeId)... se o celular mostrar os Termos do Wear OS, aceite."
        $deadline = (Get-Date).AddMinutes(3)
        while (-not (Test-Paired) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 5 }
        if (Test-Paired) { Write-Host "Pareados: o celular enxerga $watchAvd como relógio próximo." }
        else { Write-Warning 'Não pareou em 3 min. Veja o logcat do celular (WearSetup / EMULATOR_PAIRING).' }
    }
}

Write-Host ''
Write-Host "Celular: $phone | Relógio ($watchAvd): $watchSerial | GluSync: http://localhost:$GluSyncPort"
if (-not $Pair) { Write-Host ('Pareados: ' + $(if (Test-Paired) { 'sim' } else { 'não (rode com -Pair)' })) }
