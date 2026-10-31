@echo off
rem Publica a versao do app/build.gradle.kts como Release no GitHub (ver publish-release.ps1).
rem Duplo clique: roda sem a trava de scripts do Windows. Passe -DryRun para so ensaiar.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-release.ps1" %*
pause
