#!/usr/bin/env python3
"""Extrai dos APKs oficiais as bibliotecas nativas proprietárias que o GluView carrega.

O repositório do GluView NÃO distribui estas bibliotecas (são da Abbott e da Sibionics, não podem
ser redistribuídas). Quem compila extrai dos APKs oficiais que baixou por conta própria — o mesmo
modelo do Juggluco. Sem elas o app compila e funciona, só os sensores nativos (FreeStyle Libre 2
Plus e Sibionics) ficam indisponíveis.

Uso:
    python tools/extract_native_libs.py --librelink LibreLink-2.5.2.apk --sibionics SiJoy-01.01.00.00.apk

Cada arquivo gerado é conferido por SHA-256: se o APK for de outra versão, o script para sem
gravar nada daquele grupo (biblioteca diferente quebraria a leitura do sensor em silêncio).

libDataProcessing.so (Abbott) é gravada como o Juggluco faz em getlibrary.java: a .so do APK +
o bloco de assinatura do próprio APK + um final fixo de 101 bytes. O anti-tamper da biblioteca
confere essa assinatura; sem o bloco, o streaming do Libre 2 Plus não devolve glicemia. Por isso
o build não pode "stripar" essas .so (keepDebugSymbols em app/build.gradle.kts).

Requer Python 3.8+, só biblioteca padrão.
"""
import argparse
import hashlib
import struct
import sys
import zipfile
from pathlib import Path

ABI = "arm64-v8a"
OUT_DIR = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "jniLibs" / ABI

# FreeStyle LibreLink 2.5.2 (pacote com.freestylelibre.app.cn, versionCode 6349).
LIBRELINK = {
    "libDataProcessing.so": "0b58fe8c12c1164150f393902f76f1c96213f365179c903dc6c4188477cf8221",
    "libSecureKeyBoxJava.so": "2c34a4b0a30fdf3b19224b71036025b1dfafeac6808df2454d2b699b79380aeb",
    "libMathRuntimeChecks.so": "74caeb8d854c212d6e49a16ada4d7c437adf34ced500a4b6e34be785bae0d161",
}

# SIBIONICS / SiJoy 01.01.00.00 (pacote com.sisensing.sijoy).
SIBIONICS = {
    "libnative-algorithm-jni-v110.so": "2a6f21493137b2df9e6839b77e120d940ff9bf85dee93758d28e17f6051b0895",
    "libnative-algorithm-jni-v112.so": "dcb993acb1bb7c8db51c567f5d90f184811946a1368d1b5604d20f4855320ef5",
    "libnative-algorithm-v1_1_0.so": "fabbd20b9b5506b96122ba33cf120ea080d8396fdf24b4f6776edced7567d182",
    "libnative-algorithm-v1_1_2.so": "41864e961cc210218338034e173e747f35a26bf69b9ffd02fbb5895b25b2b2f2",
    "libnative-encrypy-decrypt-v110.so": "7deca2302bee4aff557c261de89565cf43744f7e6430e8d6bbd3c6823b7a92d4",
    "libnative-sensitivity-v110.so": "0abf75cdea35b01b7abd87a5e1f4507e20052ce50d36af03ed977026d179b081",
    "libnative-struct2json.so": "9365cff687dd744977dcd646c4187ee4b158af33699bdd0e0e3be83002f4572e",
}

# Final fixo anexado depois do bloco de assinatura (entrada de diretório central + EOCD), igual ao
# `end[]` de getlibrary.java do Juggluco (GPLv3).
ABBOTT_TRAILER = bytes.fromhex(
    "504b01021e030a0000000000eea1385241e4a9b20d0000000d000000090018000000000000000000"
    "b4810000000000000000000000000055540500034fc70d6075780b000104e803000004e8030000"
    "504b050600000000010001004f00000098ca28000000"
)

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def apk_signing_block(apk: bytes) -> bytes:
    """Bloco de assinatura v2+ do APK: fica logo antes do diretório central (APK Signature Scheme v2)."""
    eocd = apk.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise ValueError("APK sem End Of Central Directory")
    cd_offset = struct.unpack_from("<I", apk, eocd + 16)[0]
    if apk[cd_offset - 16:cd_offset] != APK_SIG_BLOCK_MAGIC:
        raise ValueError("APK sem bloco de assinatura v2 (baixe o APK original, sem modificações)")
    size = struct.unpack_from("<Q", apk, cd_offset - 24)[0]
    return apk[cd_offset - size - 8:cd_offset]


def extract(apk_path: Path, expected: dict, abbott: bool) -> dict:
    raw = apk_path.read_bytes()
    out = {}
    with zipfile.ZipFile(apk_path) as z:
        for name, want in expected.items():
            data = z.read(f"lib/{ABI}/{name}")
            if abbott and name == "libDataProcessing.so":
                data = data + apk_signing_block(raw) + ABBOTT_TRAILER
            got = sha256(data)
            if got != want:
                raise ValueError(f"{name}: SHA-256 {got} diferente do esperado {want} (versão errada do APK?)")
            out[name] = data
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--librelink", type=Path, help="APK do FreeStyle LibreLink 2.5.2 (arm64-v8a)")
    ap.add_argument("--sibionics", type=Path, help="APK do SIBIONICS/SiJoy 01.01.00.00 (arm64-v8a)")
    args = ap.parse_args()
    if not args.librelink and not args.sibionics:
        ap.error("informe --librelink e/ou --sibionics")

    groups = []
    if args.librelink:
        groups.append(("LibreLink", args.librelink, LIBRELINK, True))
    if args.sibionics:
        groups.append(("Sibionics", args.sibionics, SIBIONICS, False))

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    failed = False
    for label, path, expected, abbott in groups:
        try:
            libs = extract(path, expected, abbott)
        except (OSError, KeyError, ValueError, zipfile.BadZipFile) as e:
            print(f"[{label}] ERRO: {e}", file=sys.stderr)
            failed = True
            continue
        for name, data in libs.items():
            (OUT_DIR / name).write_bytes(data)
            print(f"[{label}] ok  {name}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
