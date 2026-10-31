<#
.SYNOPSIS
  Publica uma versao do GluView como Release no GitHub para atualizacao sem cabo
  (etapa E3 do plano 08_atualizacao_ota_plano.md).

.DESCRIPTION
  Roda no PC de publicação. A assinatura acontece aqui, offline: nenhuma chave vai para o CI.
  Passos, e o script para no primeiro que falhar:
    1. Arvore limpa, branch main e HEAD igual a origin/main (o que sai e o que esta no GitHub).
    2. Le o versionName do app/build.gradle.kts (fonte unica; subir a versao = commit nesse arquivo)
       e recusa se a tag v<versionName> ja existe ou se nao e maior que a ultima publicada.
    3. Build da variante release (R8, padrao desde a etapa E8) e assinatura OFFLINE com a chave A
       (debug.keystore do PC de publicação, a mesma dos aparelhos instalados: atualiza por cima sem
       perder dados). -Variant debug ainda gera a variante debug (nao debuggable), como antes.
       No release, confere no mapping.txt que as classes acessadas por nome (JNI das libs da
       Sibionics/Abbott, Gson do envio ao GluSync e da Mi Band, DTOs) nao foram renomeadas, e
       anexa o mapping.txt compactado a Release (para ler stack traces daquela versao).
    4. Confere o versionCode gerado pela formula (minutos desde 2026-01-01 UTC) e recusa se for menor
       ou igual ao ultimo publicado.
    5. Verifica: apksigner (certificado na lista permitida), zipalign 16 KB, aapt2 (pacote e versao),
       conteudo nativo (armeabi-v7a presente; .so com anti-tamper identicas as do repositorio).
    6. Gera GluView-<versao>.apk, .sha256 e manifest.json (contrato da secao 5.2 do plano).
    7. Cria a Release v<versionName> no GitHub apontando para o commit publicado.

  Autenticacao do GitHub (nunca cole token no chat nem em arquivo), uma das duas:
  - Uma vez so: `gh auth login` com token fine-grained (Contents: Read and write, so neste
    repositorio). O gh guarda o token cifrado no Gerenciador de Credenciais do Windows e o script
    passa a publicar sem pedir nada.
  - Ou, na sessao: $env:GH_TOKEN (vale mais que o login guardado).
  - -DryRun nao publica nada; com autenticacao ele tambem consulta as releases ja publicadas.

  Notas da versao: release-notes/<versao>.pt.txt e .en.txt (texto puro, sem links), ou os
  parametros -NotesPt/-NotesEn. Duplo clique em tools\publicar.cmd roda tudo sem a trava de
  scripts do Windows.

.EXAMPLE
  .\tools\publish-release.ps1 -DryRun
.EXAMPLE
  .\tools\publish-release.ps1 -NotesPt "Corrige X." -NotesEn "Fixes X."
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    # Só com -DryRun: pula a checagem de árvore limpa/main (para testar mudanças no próprio script).
    [switch]$SkipRepoCheck,
    # Só com -DryRun: reaproveita o APK já gerado (o build leva ~10 min) e não confere a fórmula.
    [switch]$SkipBuild,
    [string]$NotesPt = "",
    [string]$NotesEn = "",
    [ValidateSet("stable", "beta")][string]$Channel = "stable",
    [ValidateSet("release", "debug")][string]$Variant = "release",
    # Chave A (debug.keystore padrao do Android nesta maquina; senha publica "android" — a protecao
    # e o arquivo). Copia de seguranca fora do repositorio. A chave B entra na etapa E11.
    [string]$SigningKeystore = (Join-Path $env:USERPROFILE ".android\debug.keystore"),
    [string]$Repo = "GluSync-GluView/GluView",
    # Certificados aceitos. Hoje so a chave A (debug do PC de publicação). A chave B entra na etapa E11.
    [string[]]$AllowedCertSha256 = @("e678b39d85151bd06f3206b6bffa7f2b1bdbd65f2cb4e2c8dbe8a5584bd8c796")
)

# "Continue": no Windows PowerShell 5.1, com "Stop" qualquer linha que git/gh/gradle escrevem no
# stderr (ex.: "From https://..." do fetch) vira erro fatal. Os comandos externos são julgados pelo
# $LASTEXITCODE (sempre conferido abaixo); as operações de arquivo usam -ErrorAction Stop.
$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
$Sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$Gh = "C:\Program Files\GitHub CLI\gh.exe"
$BuildTools = Get-ChildItem (Join-Path $Sdk "build-tools") -Directory |
    Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } | Select-Object -Last 1
$ApkSigner = Join-Path $BuildTools.FullName "apksigner.bat"
$ZipAlign = Join-Path $BuildTools.FullName "zipalign.exe"
$Aapt2 = Join-Path $BuildTools.FullName "aapt2.exe"
$Package = "com.gluview.app"
# .so que o packaging NAO pode alterar (keepDebugSymbols em app/build.gradle.kts): a da Abbott tem
# assinatura anexada apos o ELF. Precisam sair identicas as do repositorio.
$StrictLibs = @("libDataProcessing.so", "libSecureKeyBoxJava.so", "libMathRuntimeChecks.so")

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "ERRO: $msg" -ForegroundColor Red; exit 1 }
function Ok($msg) { Write-Host "    ok: $msg" -ForegroundColor Green }
function RunGit { $out = & git.exe -C $Root @args 2>&1 | ForEach-Object { "$_" }; if ($LASTEXITCODE -ne 0) { Fail "git $args : $out" }; return $out }
function MinutesSince2026 { [int][Math]::Floor(([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - 1767225600000) / 60000) }
function Sha256File($path) { (Get-FileHash -Algorithm SHA256 $path).Hash.ToLowerInvariant() }

# GH_TOKEN da sessao OU o login guardado pelo `gh auth login` (Gerenciador de Credenciais do Windows).
$HasGhAuth = [bool]$env:GH_TOKEN
if (-not $HasGhAuth) {
    # --active: uma conta antiga e inativa com token vencido no keyring não pode derrubar o check.
    & $Gh auth status --hostname github.com --active *> $null
    $HasGhAuth = ($LASTEXITCODE -eq 0)
}
if (-not $DryRun -and -not $HasGhAuth) {
    Fail "sem autenticacao do GitHub: rode uma vez 'gh auth login' (token fine-grained com Contents: Read and write so neste repositorio) ou defina `$env:GH_TOKEN"
}

# ---------------------------------------------------------------------------------------------- 1
Step "1. Estado do repositorio"
if ($SkipRepoCheck -and -not $DryRun) { Fail "-SkipRepoCheck so e permitido com -DryRun" }
$head = (RunGit rev-parse HEAD).Trim()
if ($SkipRepoCheck) {
    Write-Host "    aviso: checagem do repositorio PULADA (-SkipRepoCheck)" -ForegroundColor Yellow
} else {
    $status = RunGit status --porcelain
    if ($status) { Fail "arvore com mudancas nao commitadas:`n$status" }
    $branch = (RunGit rev-parse --abbrev-ref HEAD).Trim()
    if ($branch -ne "main") { Fail "precisa estar na main (esta em '$branch')" }
    RunGit fetch origin main | Out-Null
    $remote = (RunGit rev-parse origin/main).Trim()
    if ($head -ne $remote) { Fail "HEAD ($head) difere de origin/main ($remote): faca push/pull antes" }
    Ok "main limpa e igual a origin/main ($($head.Substring(0,7)))"
}

# ---------------------------------------------------------------------------------------------- 2
Step "2. Versao e releases ja publicadas"
$gradle = Get-Content (Join-Path $Root "app\build.gradle.kts") -Raw
if ($gradle -notmatch '\?: "(\d+\.\d+\.\d+)"') { Fail "nao achei o versionName padrao em app/build.gradle.kts" }
$versionName = $Matches[1]
$tag = "v$versionName"
Ok "versionName $versionName (tag $tag)"

# Notas: parametros, ou release-notes/<versao>.pt.txt e .en.txt (escritas junto com a versao).
$notesDir = Join-Path $Root "release-notes"
foreach ($lang in "pt", "en") {
    $file = Join-Path $notesDir "$versionName.$lang.txt"
    $current = if ($lang -eq "pt") { $NotesPt } else { $NotesEn }
    if ([string]::IsNullOrWhiteSpace($current) -and (Test-Path $file)) {
        $text = ([System.IO.File]::ReadAllText($file, [System.Text.Encoding]::UTF8)).Trim()
        if ($lang -eq "pt") { $NotesPt = $text } else { $NotesEn = $text }
    }
}
if ([string]::IsNullOrWhiteSpace($NotesPt) -or [string]::IsNullOrWhiteSpace($NotesEn)) {
    if (-not $DryRun) { Fail "faltam as notas: crie release-notes/$versionName.pt.txt e .en.txt (ou use -NotesPt/-NotesEn)" }
    Write-Host "    aviso: sem notas da versao (so permitido em -DryRun)" -ForegroundColor Yellow
} else {
    Ok "notas da versao em pt e en"
}

$lastCode = 0; $lastName = [version]"0.0.0"
if ($HasGhAuth) {
    $ghErr = [System.IO.Path]::GetTempFileName()
    $relJson = (& $Gh release list --repo $Repo --limit 100 --json tagName,isDraft 2>$ghErr) -join "`n"
    $ghErrText = (Get-Content $ghErr -Raw -ErrorAction SilentlyContinue); Remove-Item $ghErr -Force -ErrorAction SilentlyContinue
    if ($LASTEXITCODE -ne 0) { Fail "gh release list falhou (token expirado/revogado?): $ghErrText" }
    $releases = $relJson | ConvertFrom-Json
    if ($releases | Where-Object { $_.tagName -eq $tag }) { Fail "a release $tag ja existe: suba o versionName no build.gradle.kts" }
    $tmpMan = Join-Path $env:TEMP "gluview-manifests"
    Remove-Item -Recurse -Force $tmpMan -ErrorAction SilentlyContinue
    foreach ($r in $releases | Where-Object { -not $_.isDraft }) {
        $dir = Join-Path $tmpMan $r.tagName
        & $Gh release download $r.tagName --repo $Repo --pattern manifest.json --dir $dir 2>&1 | Out-Null
        $mf = Join-Path $dir "manifest.json"
        if (Test-Path $mf) {
            $m = Get-Content $mf -Raw | ConvertFrom-Json
            if ($m.versionCode -gt $lastCode) { $lastCode = [int]$m.versionCode }
            if ([version]$m.versionName -gt $lastName) { $lastName = [version]$m.versionName }
        }
    }
    Ok "ultima publicada: versionName $lastName, versionCode $lastCode (releases sem manifest.json sao ignoradas)"
    if ([version]$versionName -le $lastName) { Fail "versionName $versionName nao e maior que o ultimo publicado ($lastName)" }
} else {
    Write-Host "    aviso: sem autenticacao do GitHub, nao consultei as releases publicadas (so permitido em -DryRun)" -ForegroundColor Yellow
}
$existingTag = & git.exe -C $Root ls-remote --tags origin "refs/tags/$tag" 2>&1
if ($existingTag) { Fail "a tag $tag ja existe no GitHub" }

# ---------------------------------------------------------------------------------------------- 3
Step "3. Build (variante $Variant, assinada com a chave A)"
if ($SkipBuild -and -not $DryRun) { Fail "-SkipBuild so e permitido com -DryRun" }
$V = if ($Variant -eq "release") { "Release" } else { "Debug" }
$codeBefore = MinutesSince2026
if ($SkipBuild) {
    Write-Host "    aviso: build PULADO (-SkipBuild), usando o APK existente" -ForegroundColor Yellow
} else {
    # cleanHiltJavaCompile*: o versionCode muda a cada minuto e a compilação incremental do Hilt
    # às vezes falha com "Attempt to recreate a file for type ..._GeneratedInjector" (visto em 24/10).
    & (Join-Path $Root "gradlew.bat") -p $Root ":app:cleanHiltJavaCompile$V" ":app:assemble$V" --console=plain -q
    if ($LASTEXITCODE -ne 0) { Fail "build falhou" }
}
$codeAfter = MinutesSince2026
if ($Variant -eq "release") {
    # O release sai sem assinatura (local.properties sem KEYSTORE_*: a chave nunca vai para o Gradle
    # nem para o CI). Alinha se preciso e assina aqui com apksigner.
    $unsigned = Join-Path $Root "app\build\outputs\apk\release\app-release-unsigned.apk"
    if (-not (Test-Path $unsigned)) { Fail "APK nao encontrado em $unsigned" }
    if (-not (Test-Path $SigningKeystore)) { Fail "keystore da chave A nao encontrado em $SigningKeystore" }
    $aligned = Join-Path $Root "app\build\outputs\apk\release\app-release-aligned.apk"
    & $ZipAlign -c -P 16 4 $unsigned | Out-Null
    if ($LASTEXITCODE -eq 0) { Copy-Item $unsigned $aligned -Force -ErrorAction Stop }
    else {
        & $ZipAlign -f -P 16 4 $unsigned $aligned | Out-Null
        if ($LASTEXITCODE -ne 0) { Fail "zipalign falhou" }
    }
    $apk = Join-Path $Root "app\build\outputs\apk\release\app-release-signed.apk"
    & $ApkSigner sign --ks $SigningKeystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android `
        --v1-signing-enabled false --v2-signing-enabled true --out $apk $aligned 2>&1 | ForEach-Object { "$_" } | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "apksigner sign falhou" }
} else {
    $apk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
}
if (-not (Test-Path $apk)) { Fail "APK nao encontrado em $apk" }
Ok "build ok ($Variant)"

# ---------------------------------------------------------------------------------------------- 4
Step "4. versionCode"
$badging = & $Aapt2 dump badging $apk 2>&1
if ($badging[0] -notmatch "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'") { Fail "aapt2 nao leu o pacote" }
$apkPackage = $Matches[1]; $versionCode = [int]$Matches[2]; $apkVersionName = $Matches[3]
if ($apkPackage -ne $Package) { Fail "pacote $apkPackage, esperado $Package" }
if ($apkVersionName -ne $versionName) { Fail "versionName do APK ($apkVersionName) difere do build.gradle.kts ($versionName)" }
if (-not $SkipBuild -and ($versionCode -lt ($codeBefore - 2) -or $versionCode -gt ($codeAfter + 2))) {
    Fail "versionCode $versionCode fora da formula (esperado entre $codeBefore e $codeAfter): build com -Pgluview.versionCode?"
}
if ($versionCode -le $lastCode) { Fail "versionCode $versionCode nao e maior que o ultimo publicado ($lastCode)" }
if ($badging | Select-String "^application-debuggable") { Fail "APK depuravel: nunca publicar (anti-tamper da Abbott e seguranca)" }
$sdkLine = $badging | Select-String "^minSdkVersion:'(\d+)'" | Select-Object -First 1
if (-not $sdkLine) { Fail "aapt2 nao mostrou o minSdk" }
$minSdk = [int]$sdkLine.Matches[0].Groups[1].Value
Ok "versionCode $versionCode, minSdk $minSdk"

# ---------------------------------------------------------------------------------------------- 5
Step "5. Assinatura, alinhamento e conteudo nativo"
$certs = & $ApkSigner verify --print-certs -v $apk 2>&1
if ($LASTEXITCODE -ne 0) { Fail "apksigner verify falhou:`n$certs" }
$certShas = @($certs | Select-String "certificate SHA-256 digest: ([0-9a-f]{64})" | ForEach-Object { $_.Matches[0].Groups[1].Value })
if ($certShas.Count -eq 0) { Fail "apksigner nao mostrou o certificado" }
foreach ($c in $certShas) { if ($AllowedCertSha256 -notcontains $c) { Fail "certificado $c fora da lista permitida" } }
Ok "certificado $($certShas -join ', ')"

& $ZipAlign -c -P 16 4 $apk | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "zipalign -c -P 16 4 falhou" }
Ok "zipalign (16 KB para .so)"

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
try {
    $entries = $zip.Entries | ForEach-Object { $_.FullName }
    if (-not ($entries | Where-Object { $_ -like "lib/armeabi-v7a/*.so" })) { Fail "sem lib/armeabi-v7a: o Moto (32 bits) nao instalaria" }
    $jni = Join-Path $Root "app\src\main\jniLibs\arm64-v8a"
    # As .so proprietarias nao estao no Git (tools/extract_native_libs.py): sem elas o APK sairia sem
    # Libre 2 Plus e Sibionics, sem nenhum erro de build. Publicar exige as 10 presentes.
    $required = @("libDataProcessing.so", "libSecureKeyBoxJava.so", "libMathRuntimeChecks.so",
        "libnative-algorithm-jni-v110.so", "libnative-algorithm-jni-v112.so", "libnative-algorithm-v1_1_0.so",
        "libnative-algorithm-v1_1_2.so", "libnative-encrypy-decrypt-v110.so", "libnative-sensitivity-v110.so",
        "libnative-struct2json.so")
    foreach ($name in $required) {
        if (-not (Test-Path (Join-Path $jni $name))) { Fail "falta $name em jniLibs: rode tools/extract_native_libs.py" }
    }
    foreach ($so in Get-ChildItem $jni -Filter *.so) {
        $entry = $zip.GetEntry("lib/arm64-v8a/$($so.Name)")
        if (-not $entry) { Fail "falta lib/arm64-v8a/$($so.Name) no APK" }
        $tmp = [System.IO.Path]::GetTempFileName()
        try {
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $tmp, $true)
            $same = (Sha256File $tmp) -eq (Sha256File $so.FullName)
        } finally { Remove-Item $tmp -Force }
        if ($StrictLibs -contains $so.Name) {
            if (-not $same) { Fail "$($so.Name) foi alterada no empacotamento (anti-tamper da Abbott quebraria)" }
        } elseif (-not $same) {
            Write-Host "    aviso: $($so.Name) difere do repositorio (strip de simbolos?)" -ForegroundColor Yellow
        }
    }
} finally { $zip.Dispose() }
Ok "armeabi-v7a presente; .so com anti-tamper identicas"

$mapping = Join-Path $Root "app\build\outputs\mapping\release\mapping.txt"
if ($Variant -eq "release") {
    # Classes que as libs nativas (JNI/RegisterNatives/Class.forName) e o Gson acessam pelo NOME. Se
    # o R8 renomear qualquer uma, a função quebra em silêncio no aparelho (E8 do plano OTA).
    if (-not (Test-Path $mapping)) { Fail "mapping.txt nao encontrado em $mapping" }
    $mustKeep = @(
        "com.algorithm.v1_1_2.NativeAlgorithmLibraryV1_1_2",
        "com.algorithm.v1_1_2.AlgorithmContext",
        "com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.DataProcessingNative",
        "com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.DataProcessingOutputs",
        "com.gluview.app.data.libre2.Libre2Native",
        'com.gluview.app.data.remote.GluSyncUploader$Entry',
        "com.gluview.app.watchface.miband.firmware.WatchFaceParts.ConfigPOJO.WatchfaceConfig",
        "com.gluview.app.data.remote.dto.NovaMedicaoDto",
        "com.gluview.app.data.remote.dto.UpdateLatestDto",
        "com.gluview.app.data.remote.dto.UpdateDownloadLinkDto"
    )
    $classLines = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($line in [System.IO.File]::ReadLines($mapping)) { if ($line.Length -gt 0 -and $line[0] -ne ' ' -and $line[0] -ne '#') { [void]$classLines.Add($line) } }
    foreach ($c in $mustKeep) {
        if (-not $classLines.Contains("$c -> ${c}:")) { Fail "R8 renomeou ou removeu $c (regra keep em app/proguard-rules.pro)" }
    }
    Ok "R8: $($mustKeep.Count) classes acessadas por nome mantidas"
}

# ---------------------------------------------------------------------------------------------- 6
Step "6. Artefatos"
$out = Join-Path $Root "app\build\outputs\publish\$tag"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $out -ErrorAction Stop | Out-Null
$apkName = "GluView-$versionName.apk"
$apkOut = Join-Path $out $apkName
Copy-Item $apk $apkOut -ErrorAction Stop
$sha = Sha256File $apkOut
$size = (Get-Item $apkOut).Length
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Join-Path $out "$apkName.sha256"), "$sha  $apkName`n", $utf8)
$manifest = [ordered]@{
    schema      = 1
    package     = $Package
    versionCode = $versionCode
    versionName = $versionName
    channel     = $Channel
    variant     = $Variant
    sizeBytes   = $size
    sha256      = $sha
    minSdk      = $minSdk
    certSha256  = $certShas
    commit      = $head
    publishedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    notes       = [ordered]@{ pt = $NotesPt; en = $NotesEn }
}
$manifestJson = $manifest | ConvertTo-Json -Depth 4
[System.IO.File]::WriteAllText((Join-Path $out "manifest.json"), $manifestJson, $utf8)
Ok "$apkName ($([Math]::Round($size / 1MB, 1)) MB), sha256 $sha"
$extraAssets = @()
if ($Variant -eq "release") {
    # mapping.txt (~77 MB) compactado: sem ele, stack trace do release é ilegível. Fica como anexo
    # da própria Release (o backend ignora anexos que não sejam o .apk e o manifest.json).
    $mappingZip = Join-Path $out "mapping-$versionName.zip"
    Compress-Archive -Path $mapping -DestinationPath $mappingZip -CompressionLevel Optimal -Force -ErrorAction Stop
    $extraAssets += $mappingZip
    Ok "mapping.txt compactado ($([Math]::Round((Get-Item $mappingZip).Length / 1MB, 1)) MB)"
}
Write-Host $manifestJson

if ($DryRun) {
    Write-Host "`nDryRun: nada publicado. Artefatos em $out" -ForegroundColor Yellow
    exit 0
}

# ---------------------------------------------------------------------------------------------- 7
Step "7. Publicando a release $tag em $Repo"
$notesFile = Join-Path $out "release-notes.md"
[System.IO.File]::WriteAllText($notesFile, "$NotesPt`n`n---`n`n$NotesEn`n`nversionCode $versionCode | sha256 $sha`n", $utf8)
$ghArgs = @("release", "create", $tag, $apkOut, (Join-Path $out "$apkName.sha256"), (Join-Path $out "manifest.json"),
    "--repo", $Repo, "--target", $head, "--title", "GluView $versionName", "--notes-file", $notesFile)
$ghArgs = @($ghArgs[0..5]) + $extraAssets + @($ghArgs[6..($ghArgs.Count - 1)])
if ($Channel -eq "beta") { $ghArgs += "--prerelease" }
& $Gh @ghArgs
if ($LASTEXITCODE -ne 0) { Fail "gh release create falhou" }
Ok "release $tag publicada (commit $($head.Substring(0,7)), versionCode $versionCode)"
if ($env:GH_TOKEN) { Write-Host "`nLembrete: revogue o token de escrita se ele nao for mais necessario." -ForegroundColor Yellow }
