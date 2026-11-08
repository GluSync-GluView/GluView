#!/usr/bin/env bash
# Gera os APKs-modelo do visor personalizado no Wear OS (plano 16, W2) e grava nos assets do app:
#   app/src/main/assets/wff/visor_template.apk   (pacote com.gluview.app.watchfacepush.visor)
#   app/src/demo/assets/wff/visor_template.apk   (pacote com.gluview.app.demo.watchfacepush.visor)
# O modelo NÃO é assinado: o celular troca res/raw/watchface.xml e assina com a chave do aparelho.
# Rodar de novo só se mudar algo em tools/wff/visor-template (requer build-tools 36.1.0 e android-36.1).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$SDK/build-tools/36.1.0"; PLAT="$SDK/platforms/android-36.1"
SRC="$ROOT/tools/wff/visor-template"; TMP="$(mktemp -d)"
"$BT/aapt2" compile --dir "$SRC/res" -o "$TMP/res.zip" 2>/dev/null || "$BT/aapt2.exe" compile --dir "$SRC/res" -o "$TMP/res.zip"
for pair in "com.gluview.app:main" "com.gluview.app.demo:demo"; do
  pkg="${pair%%:*}"; set_="${pair##*:}"
  sed "s/@PKG@/$pkg/" "$SRC/AndroidManifest.xml" > "$TMP/AndroidManifest.xml"
  AAPT="$BT/aapt2"; [ -x "$AAPT" ] || AAPT="$BT/aapt2.exe"
  "$AAPT" link -o "$TMP/$set_.apk" -I "$PLAT/android.jar" --manifest "$TMP/AndroidManifest.xml" "$TMP/res.zip"
  mkdir -p "$ROOT/app/src/$set_/assets/wff"
  cp "$TMP/$set_.apk" "$ROOT/app/src/$set_/assets/wff/visor_template.apk"
  echo "ok: $set_ -> $pkg.watchfacepush.visor ($(wc -c < "$TMP/$set_.apk") bytes)"
done
