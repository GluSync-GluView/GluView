# Funções comuns da chave B (rotação A→B, plano OTA seção 6). Carregar com: . "$PSScriptRoot\chave-b.ps1"
#
# A senha da chave B nunca vai para arquivo de texto, log, Gradle nem CI. Duas formas de fornecê-la:
#  - guardada uma vez com tools\guardar-senha-b.ps1: fica cifrada pelo Windows (DPAPI) em
#    %USERPROFILE%\.gluview-keys\senha-b.dpapi — só este usuário, neste PC, consegue decifrar;
#  - ou digitada na hora (pedido escondido), quando o arquivo não existe.
# Em ambos os casos ela só existe em memória, na variável de ambiente GLUVIEW_B_PASS deste processo,
# que o apksigner lê (--ks-pass env:) e que é apagada logo depois.

$GluviewKeyB      = Join-Path $env:USERPROFILE ".gluview-keys\gluview-release.p12"
$GluviewKeyBAlias = "gluview-b"
$GluviewKeyBSha   = "eceb6c6bd9128959483cdf9dcb5628efc3513bea73db8613024769bb01b5e034"
$GluviewKeyBPassFile = Join-Path $env:USERPROFILE ".gluview-keys\senha-b.dpapi"
$GluviewLineage   = Join-Path (Split-Path -Parent $PSScriptRoot) "signing\lineage.bin"

function Enable-GluviewKeyBPassword {
    if (Test-Path $GluviewKeyBPassFile) {
        $secure = (Get-Content $GluviewKeyBPassFile -Raw).Trim() | ConvertTo-SecureString  # Trim: a quebra de linha final quebra a decifragem
    } else {
        $secure = Read-Host "Senha da chave B" -AsSecureString
    }
    $env:GLUVIEW_B_PASS = [System.Net.NetworkCredential]::new('', $secure).Password
}

function Disable-GluviewKeyBPassword { Remove-Item Env:GLUVIEW_B_PASS -ErrorAction SilentlyContinue }

# Assina $Aligned (já alinhado em 16 KB) só com B + lineage, APK Signature Scheme v3, rotação a partir
# do Android 9 (minSdk 28). Exige Enable-GluviewKeyBPassword antes. Devolve o código de saída.
function Invoke-GluviewSignB([string]$ApkSigner, [string]$Aligned, [string]$Out) {
    if (-not (Test-Path $GluviewKeyB)) { throw "chave B não encontrada em $GluviewKeyB" }
    if (-not (Test-Path $GluviewLineage)) { throw "lineage não encontrado em $GluviewLineage" }
    & $ApkSigner sign --rotation-min-sdk-version 28 --min-sdk-version 28 `
        --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true `
        --ks $GluviewKeyB --ks-key-alias $GluviewKeyBAlias --ks-pass env:GLUVIEW_B_PASS `
        --lineage $GluviewLineage --debuggable-apk-permitted false --out $Out $Aligned 2>&1 | ForEach-Object { "$_" } | Out-Null
    return $LASTEXITCODE
}
