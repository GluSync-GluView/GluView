<#
.SYNOPSIS
  Guarda a senha da chave B cifrada pelo Windows (DPAPI), uma vez, para publicar e instalar builds
  de teste sem digitá-la a cada vez.

.DESCRIPTION
  Pede a senha escondida, confere que ela abre a chave B (keytool) e grava o resultado cifrado em
  %USERPROFILE%\.gluview-keys\senha-b.dpapi. Só este usuário do Windows, neste PC, consegue decifrar
  (o arquivo copiado para outro lugar não serve para nada). Para deixar de guardar: apague o arquivo.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\guardar-senha-b.ps1
#>
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\chave-b.ps1"
$keytool = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
if (-not (Test-Path $GluviewKeyB)) { throw "chave B não encontrada em $GluviewKeyB" }

$secure = Read-Host "Senha da chave B" -AsSecureString
$env:GLUVIEW_B_PASS = [System.Net.NetworkCredential]::new('', $secure).Password
try {
    & $keytool -list -keystore $GluviewKeyB -storetype PKCS12 -alias $GluviewKeyBAlias -storepass:env GLUVIEW_B_PASS *> $null
    if ($LASTEXITCODE -ne 0) { throw "a senha não abre a chave B — nada foi gravado" }
} finally {
    Disable-GluviewKeyBPassword
}
$secure | ConvertFrom-SecureString | Set-Content -Path $GluviewKeyBPassFile -Encoding ASCII
Write-Host "ok: senha da chave B guardada cifrada (DPAPI) em $GluviewKeyBPassFile" -ForegroundColor Green
