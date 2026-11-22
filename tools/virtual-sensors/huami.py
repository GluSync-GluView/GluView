"""Pulseira Huami virtual (Mi Band 6 e família) — plano 21, F7.1/F7.2 — Bumble + netsim do emulador.

Faz o lado da PULSEIRA do caminho que o GluView já usa com a Mi Band 6 de verdade (MiBandUploader,
porte do WatchDrip FirmwareOperations2020):

  Autenticação (char 0009, serviço FEE1), flags da Mi Band 6 (authFlags 0x00, cryptFlags 0x80):
    app → [82 00 02 01 00]            pulseira → [10 82 01] + 16 bytes aleatórios
    app → [83 00] + AES-128-ECB(aleatório, chave)   pulseira → [10 83 01] (ou [10 83 04] chave errada)
  Envio de visor (control 1531 com notificação, dados 1532 sem resposta):
    d0 → [10 d0 01 00 <chunk LE16>]   d2 tipo+tamanho+CRC32+chunk → [10 d2 01]
    d3 01 → [10 d3 01]   dados em pedaços; a cada pedaço completo → [10 d4 <próximo offset LE32>]
    d5 → [10 d5 01] se o CRC32 do recebido bate (senão [10 d5 04])   d6 → [10 d6 01]
  Config (0003): WF_ID (39 00 …) e modo noturno (1a 01 …) só são registrados.

O visor recebido é gravado em `huami-recebido-<n>.bin` (cabeçalho conferido: UIHH = Mi Band 4/5/6). A chave da
pulseira virtual é de TESTE (`--key`), nunca a de uma pulseira real. Cenários (control-huami.txt):
`wrongkey` (próxima autenticação recusa), `dropmid` (cai no meio do envio), `slow` (atraso nos acks).

Modelos (`--model`, F7.2): o protocolo, as flags de autenticação e o cabeçalho esperado do visor seguem
o WatchDrip+ (`MiBandType`, `MiBandService`, `Sequence/*`), igual ao `HuamiProfile` do app:
  2020     — Mi Band 6, GTR 47, GTR 2/GTS 2/T-Rex Pro, GTS 2 mini, Bip S (d0 d2 d3 d4… d5 d6)
  new      — Mi Band 4/5, Amazfit Band 5, GTR 42 (01 FF [A0] 01 08 tam crc32, 03 01, dados + 00, 04)
  original — Bip / Bip Lite (01 00 00 00 FF, 01 tam24 08, 03 + dados → 10 03 01, 04 crc16)
Visores comprimidos (família GTR) são descomprimidos aqui (QuickLZ nível 3, porte do QuickLZ.java) para
conferir o cabeçalho de verdade.

Só dados sintéticos. GPLv3 (como o GluView).
"""
from __future__ import annotations

import argparse
import asyncio
import os
import struct
import zlib

from bumble.att import Attribute
from bumble.gatt import Characteristic, CharacteristicValue, Service
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from base import hexs, log, run_peripheral, setup_logging

P = Characteristic.Properties
RW = Attribute.Permissions.READABLE | Attribute.Permissions.WRITEABLE
BASE = "-0000-3512-2118-0009af100700"
SVC_MIBAND = "0000FEE0-0000-1000-8000-00805F9B34FB"
SVC_AUTH = "0000FEE1-0000-1000-8000-00805F9B34FB"
SVC_FW = "00001530" + BASE
CH_CONFIG = "00000003" + BASE
CH_EVENT = "00000010" + BASE
CH_AUTH = "00000009" + BASE
CH_FW = "00001531" + BASE
CH_FW_DATA = "00001532" + BASE
CH_CHUNKED = "00000020" + BASE
SVC_IMMEDIATE = "00001802-0000-1000-8000-00805F9B34FB"
CH_ALERT_LEVEL = "00002A06-0000-1000-8000-00805F9B34FB"
SVC_ALERT_NOTIF = "00001811-0000-1000-8000-00805F9B34FB"
CH_NEW_ALERT = "00002A46-0000-1000-8000-00805F9B34FB"


# id do modelo (HuamiLegacyModel) → protocolo, authFlags, cryptFlags, cabeçalho esperado, A0, bytes sem compressão
MODELS = {
    "mi_band_6": ("2020", 0x00, 0x80, b"UIHH\x01\x00", False, None),
    "mi_band_5": ("new", 0x00, 0x80, b"UIHH\x01\x00", True, None),
    "amazfit_band5": ("new", 0x00, 0x80, b"UIHH\x01\x00", True, None),
    "mi_band_4": ("new", 0x00, 0x80, b"HMDIAL\x00", False, None),
    "amazfit_gtr_47": ("2020", 0x00, 0x80, b"HMDIAL\x00", False, 0),
    "amazfit_gtr_42": ("new", 0x00, 0x80, b"HMDIAL\x00", True, 0),
    "amazfit_gtr2": ("2020", 0x00, 0x80, b"UIHH\x02\x00", False, 40),
    "amazfit_gts2": ("2020", 0x00, 0x80, b"UIHH\x02\x00", False, 40),
    "amazfit_t_rex_pro": ("2020", 0x00, 0x80, b"UIHH\x02\x00", False, 40),
    "amazfit_gts2_mini": ("2020", 0x00, 0x80, b"UIHH\x01\x00", False, None),
    "amazfit_bip_s": ("2020", 0x00, 0x80, b"HMDIAL\x00", False, None),
    "amazfit_bip_s_lite": ("2020", 0x00, 0x80, b"HMDIAL\x00", False, None),
    "amazfit_bip": ("original", 0x08, 0x00, b"HMDIAL\x00", False, None),
    "amazfit_bip_lite": ("original", 0x00, 0x80, b"HMDIAL\x00", False, None),
}


def _rd(a: bytes, i: int, n: int) -> int:
    return int.from_bytes(a[i:i + n].ljust(n, b"\0"), "little")


def qlz_decompress(src: bytes) -> bytes:
    """QuickLZ 1.5.0 nível 3 (só o necessário) — porte de QuickLZ.decompress."""
    hl = 9 if src[0] & 2 else 3
    size = _rd(src, 5, 4) if hl == 9 else src[2]
    if ((src[0] >> 2) & 3) != 3:
        raise ValueError("só nível 3")
    if (src[0] & 1) != 1:
        return src[hl:hl + size]
    dst = bytearray(size)
    s, d, cword, fetch = hl, 0, 1, 0
    last_matchstart = size - 6 - 4 - 1
    while True:
        if cword == 1:
            cword = _rd(src, s, 4)
            s += 4
            if d <= last_matchstart:
                fetch = _rd(src, s, 4)
        if cword & 1:
            cword >>= 1
            if fetch & 3 == 0:
                off, ml, s = (fetch & 0xFF) >> 2, 3, s + 1
            elif fetch & 2 == 0:
                off, ml, s = (fetch & 0xFFFF) >> 2, 3, s + 2
            elif fetch & 1 == 0:
                off, ml, s = (fetch & 0xFFFF) >> 6, ((fetch >> 2) & 15) + 3, s + 2
            elif (fetch & 127) != 3:
                off, ml, s = (fetch >> 7) & 0x1FFFF, ((fetch >> 2) & 0x1F) + 2, s + 3
            else:
                off, ml, s = fetch >> 15, ((fetch >> 7) & 255) + 3, s + 4
            o2 = d - off
            for i in range(ml):
                dst[d + i] = dst[o2 + i]
            d += ml
            fetch = _rd(src, s, 4)
        elif d <= last_matchstart:
            dst[d] = src[s]
            d += 1
            s += 1
            cword >>= 1
            fetch = ((fetch >> 8) & 0xFFFF) | (_rd(src, s + 2, 1) << 16) | (_rd(src, s + 3, 1) << 24)
        else:
            while d <= size - 1:
                if cword == 1:
                    s += 4
                    cword = 0x80000000
                dst[d] = src[s]
                d += 1
                s += 1
                cword >>= 1
            return bytes(dst)


def decompress_watchface(data: bytes, head: int) -> bytes:
    """Blocos QuickLZ de 4 KB um atrás do outro; o último bloco incompleto vai cru (como o WatchDrip+)."""
    out = bytearray(data[:head])
    pos = head
    while pos < len(data):
        b0 = data[pos]
        rem = len(data) - pos
        if rem >= 9 and b0 & 0x02 and ((b0 >> 2) & 3) == 3:
            csize, dsize = _rd(data, pos + 1, 4), _rd(data, pos + 5, 4)
            if dsize == 0x1000 and 9 <= csize <= rem:
                out += qlz_decompress(data[pos:pos + csize])
                pos += csize
                continue
        out += data[pos:]
        break
    return bytes(out)


def crc16(seq: bytes) -> int:
    """FirmwareOperation.getCRC16 do WatchDrip+ (protocolo original do Bip)."""
    crc = 0xFFFF
    for b in seq:
        crc = ((crc >> 8) | (crc << 8)) & 0xFFFF
        crc ^= b
        crc ^= (crc & 0xFF) >> 4
        crc ^= (crc << 12) & 0xFFFF
        crc ^= ((crc & 0xFF) << 5) & 0xFFFF
    return crc & 0xFFFF


class Band:
    def __init__(self, args) -> None:
        self.key = bytes.fromhex(args.key)
        self.model = args.model
        self.proto, self.auth_flags, self.crypt_flags, self.signature, self.a0, self.compressed_head = MODELS[args.model]
        self.syncs = 0
        self.call_answer = None   # "reject" | "ignore" | None (cenário da próxima chamada)
        self.alerts = 0
        self.chunk_len = args.chunk
        self.device = None
        self.random = b""
        self.authed = False
        self.expected_size = 0
        self.expected_crc = 0
        self.buf = bytearray()
        self.chunk_start = 0
        self.transferring = False
        self.wrong_key_next = False
        self.drop_mid = False
        self.slow = False
        self.count = 0

    def services(self) -> list[Service]:
        self.auth = Characteristic(CH_AUTH, P.WRITE | P.WRITE_WITHOUT_RESPONSE | P.NOTIFY, RW,
                                   CharacteristicValue(write=self.on_auth))
        self.config = Characteristic(CH_CONFIG, P.WRITE | P.WRITE_WITHOUT_RESPONSE | P.NOTIFY, RW,
                                     CharacteristicValue(write=self.on_config))
        self.event = Characteristic(CH_EVENT, P.NOTIFY | P.READ, RW, b"")
        self.fw = Characteristic(CH_FW, P.WRITE | P.WRITE_WITHOUT_RESPONSE | P.NOTIFY, RW,
                                 CharacteristicValue(write=self.on_fw))
        self.fw_data = Characteristic(CH_FW_DATA, P.WRITE_WITHOUT_RESPONSE | P.WRITE, RW,
                                      CharacteristicValue(write=self.on_data))
        self.chunked = Characteristic(CH_CHUNKED, P.WRITE | P.WRITE_WITHOUT_RESPONSE | P.NOTIFY, RW,
                                      CharacteristicValue(write=self.on_chunked))
        self.alert_level = Characteristic(CH_ALERT_LEVEL, P.WRITE_WITHOUT_RESPONSE | P.WRITE, RW,
                                          CharacteristicValue(write=self.on_alert_level))
        self.new_alert = Characteristic(CH_NEW_ALERT, P.WRITE | P.WRITE_WITHOUT_RESPONSE, RW,
                                        CharacteristicValue(write=self.on_new_alert))
        return [Service(SVC_MIBAND, [self.config, self.event, self.chunked]),
                Service(SVC_IMMEDIATE, [self.alert_level]),
                Service(SVC_ALERT_NOTIF, [self.new_alert]),
                Service(SVC_AUTH, [self.auth]),
                Service(SVC_FW, [self.fw, self.fw_data])]

    async def notify(self, ch, value: bytes) -> None:
        if self.slow:
            await asyncio.sleep(0.4)
        await self.device.notify_subscribers(ch, value)

    # ---- autenticação
    def on_auth(self, _conn, value: bytes) -> None:
        v = bytes(value)
        log.info("AUTH <- %s", hexs(v))
        asyncio.get_event_loop().create_task(self.handle_auth(v))

    async def handle_auth(self, v: bytes) -> None:
        await asyncio.sleep(0.03)
        if v[:1] and (v[0] & 0x0F) == 0x02:                  # pede número aleatório
            # Byte de autenticação tem de ser o do modelo (Bip 0x08, os outros 0x00). Formato: 2 bytes sem
            # cryptFlags (o que o botão "Conectar e autenticar" manda, e a Mi Band 6 real aceita) ou 5 bytes
            # com 0x80 nos modelos de flags alternativas (o que o envio de visor manda).
            flags_ok = len(v) >= 2 and v[1] == self.auth_flags and (
                ((v[0] & 0xF0) == 0x00 and len(v) == 2) or
                (self.crypt_flags == 0x80 and (v[0] & 0xF0) == 0x80 and len(v) == 5))
            if not flags_ok:
                log.info("Pedido de autenticação com flags ERRADAS para %s: %s", self.model, hexs(v))
                await self.notify(self.auth, bytes([0x10, v[0], 0x04]))
                return
            self.random = os.urandom(16)
            await self.notify(self.auth, bytes([0x10, v[0], 0x01]) + self.random)
        elif v[:1] and (v[0] & 0x0F) == 0x03 and len(v) >= 18:  # número cifrado
            enc = Cipher(algorithms.AES(self.key), modes.ECB()).encryptor()
            expected = enc.update(self.random) + enc.finalize()
            ok = v[2:18] == expected and not self.wrong_key_next
            self.wrong_key_next = False
            self.authed = ok
            log.info("Autenticação %s", "ACEITA" if ok else "RECUSADA")
            await self.notify(self.auth, bytes([0x10, v[0], 0x01 if ok else 0x04]))

    # ---- alertas (F7.1): texto em 2A46, vibração em 2A06, encerrar chamada em 0020
    def on_new_alert(self, _conn, value: bytes) -> None:
        v = bytes(value)
        if not self.authed:
            log.info("ALERTA sem autenticação: ignorado")
            return
        cat = {3: "CHAMADA", 5: "SMS"}.get(v[0], f"categoria {v[0]}")
        self.alerts += 1
        log.info("ALERTA na pulseira [%s]: %s", cat, v[2:].decode("utf-8", "replace"))
        if v[0] == 3 and self.call_answer:
            answer, self.call_answer = self.call_answer, None
            code = 0x07 if answer == "reject" else 0x09
            async def respond():
                await asyncio.sleep(1.0)
                log.info("Pessoa %s a chamada (evento %02x)", "RECUSOU" if code == 7 else "IGNOROU", code)
                await self.notify(self.event, bytes([code]))
            asyncio.get_event_loop().create_task(respond())

    def on_alert_level(self, _conn, value: bytes) -> None:
        v = bytes(value)
        if v[:1] == b"\xff" and len(v) >= 6:
            on, off = struct.unpack_from("<HH", v, 1)
            log.info("VIBRAÇÃO: %d vez(es), %d ms ligada / %d ms pausa", v[5], on, off)
        else:
            log.info("VIBRAÇÃO nível %s", hexs(v))

    def on_chunked(self, _conn, value: bytes) -> None:
        v = bytes(value)
        log.info("CHUNKED <- %s%s", hexs(v), " (encerra a chamada)" if v[:2] == b"\x00\xc0" else "")

    def on_config(self, _conn, value: bytes) -> None:
        log.info("CONFIG <- %s", hexs(bytes(value)))

    # ---- envio do visor
    def on_fw(self, _conn, value: bytes) -> None:
        v = bytes(value)
        log.info("FW <- %s", hexs(v))
        if v[:1] in (b"\x03", b"\xd3") and self.authed:
            # Abre a recepção JÁ (no Bip os dados vêm logo atrás do 03, sem esperar resposta).
            self.buf = bytearray()
            self.chunk_start = 0
            self.syncs = 0
            self.transferring = True
        asyncio.get_event_loop().create_task(self.handle_fw(v))

    async def handle_fw(self, v: bytes) -> None:
        await asyncio.sleep(0.02)
        if not self.authed:
            log.info("Comando de firmware SEM autenticação: ignorado")
            return
        op = v[0]
        if self.proto != "2020":
            await self.handle_old(v)
            return
        if op == 0xD0:
            await self.notify(self.fw, bytes([0x10, 0xD0, 0x01, 0x00]) + struct.pack("<H", self.chunk_len))
        elif op == 0xD2:
            kind = v[1]
            self.expected_size, self.expected_crc = struct.unpack_from("<II", v, 2)
            chunk = struct.unpack_from("<H", v, 10)[0]
            log.info("FW_INFO tipo=%d tamanho=%d crc32=%08x chunk=%d", kind, self.expected_size, self.expected_crc, chunk)
            await self.notify(self.fw, bytes([0x10, 0xD2, 0x01]))
        elif op == 0xD3:
            await self.notify(self.fw, bytes([0x10, 0xD3, 0x01]))
        elif op == 0xD5:
            crc = zlib.crc32(bytes(self.buf)) & 0xFFFFFFFF
            ok = len(self.buf) == self.expected_size and crc == self.expected_crc
            log.info("COMPLETE: recebido %d/%d bytes, crc32 %08x (%s)", len(self.buf), self.expected_size, crc,
                     "CONFERE" if ok else "NÃO CONFERE")
            await self.notify(self.fw, bytes([0x10, 0xD5, 0x01 if ok else 0x04]))
        elif op == 0xD6:
            self.count += 1
            name = f"huami-recebido-{self.count}.bin"
            with open(name, "wb") as f:
                f.write(self.buf)
            self.check_watchface(name)
            self.transferring = False
            await self.notify(self.fw, bytes([0x10, 0xD6, 0x01]))

    def check_watchface(self, name: str) -> None:
        data = bytes(self.buf)
        note = ""
        if self.compressed_head is not None:
            try:
                plain = decompress_watchface(data, self.compressed_head)
                note = f", descomprimido {len(data)} -> {len(plain)} bytes"
                data = plain
            except Exception as e:  # noqa: BLE001
                note = f", FALHA ao descomprimir: {e}"
        head = data[:len(self.signature)]
        ok = head == self.signature
        log.info("FINALIZE %s: visor instalado (%d bytes%s), cabeçalho %s %s -> %s", self.model, len(self.buf), note,
                 hexs(data[:8]), "CONFERE" if ok else "NÃO CONFERE (esperado " + hexs(self.signature) + ")", name)

    # ---- protocolos New (Mi Band 4/5, GTR 42) e original (Bip)
    async def handle_old(self, v: bytes) -> None:
        op = v[0]
        if op == 0x01 and len(v) == 2 and v[1] == 0xFF and self.proto == "new":
            await self.notify(self.fw, bytes([0x10, 0x01, 0x01]))                      # preparo
        elif op == 0x01 and len(v) == 5 and v[1:] == b"\x00\x00\x00\xff" and self.proto == "original":
            await self.notify(self.fw, bytes([0x10, 0x01, 0x01]))                      # preparo
        elif op == 0x01 and len(v) == 10 and self.proto == "new":
            self.expected_size, self.expected_crc = struct.unpack_from("<II", v, 2)
            log.info("FW_INFO (new) tipo=%d tamanho=%d crc32=%08x", v[1], self.expected_size, self.expected_crc)
            await self.notify(self.fw, bytes([0x10, 0x01, 0x01]))
        elif op == 0x01 and len(v) == 5 and self.proto == "original":
            self.expected_size = v[1] | (v[2] << 8) | (v[3] << 16)
            log.info("FW_INFO (original) tamanho=%d tipo=%d", self.expected_size, v[4])
            await self.notify(self.fw, bytes([0x10, 0x01, 0x01]))
        elif op == 0xA0:
            await self.notify(self.fw, bytes([0x10, 0xA0, 0x01 if self.a0 else 0x04]))
        elif op == 0x03:
            if self.proto == "new":
                await self.notify(self.fw, bytes([0x10, 0x03, 0x01]))
        elif op == 0x00:
            self.syncs += 1
        elif op == 0x04:
            self.transferring = False
            got = bytes(self.buf)
            if self.proto == "new":
                crc = zlib.crc32(got) & 0xFFFFFFFF
                ok = len(got) == self.expected_size and crc == self.expected_crc
                log.info("CHECKSUM (new): %d/%d bytes, crc32 %08x (%s), %d sincronias", len(got), self.expected_size, crc,
                         "CONFERE" if ok else "NÃO CONFERE", self.syncs)
            else:
                want = struct.unpack_from("<H", v, 1)[0] if len(v) >= 3 else -1
                crc = crc16(got)
                ok = len(got) == self.expected_size and crc == want
                log.info("CHECKSUM (original): %d/%d bytes, crc16 %04x/%04x (%s), %d sincronias", len(got),
                         self.expected_size, crc, want, "CONFERE" if ok else "NÃO CONFERE", self.syncs)
            if ok:
                self.count += 1
                name = f"huami-recebido-{self.count}.bin"
                with open(name, "wb") as f:
                    f.write(got)
                self.check_watchface(name)
            await self.notify(self.fw, bytes([0x10, 0x04, 0x01 if ok else 0x04]))

    def on_data(self, _conn, value: bytes) -> None:
        if not self.transferring:
            log.info("DADOS fora da transferência (%d bytes): ignorados", len(value))
            return
        self.buf.extend(bytes(value))
        if self.proto != "2020":
            # original: a pulseira confirma 10 03 01 quando recebeu tudo; new: só junta até o 04
            if self.proto == "original" and len(self.buf) >= self.expected_size:
                asyncio.get_event_loop().create_task(self.notify(self.fw, bytes([0x10, 0x03, 0x01])))
            return
        expected_chunk = min(self.chunk_len, self.expected_size - self.chunk_start)
        if len(self.buf) - self.chunk_start >= expected_chunk:
            self.chunk_start = len(self.buf)
            if self.drop_mid and self.chunk_start * 2 >= self.expected_size:
                self.drop_mid = False
                log.info("CENÁRIO dropmid: derrubando a conexão no meio do envio")
                for conn in list(self.device.connections.values()):
                    asyncio.get_event_loop().create_task(conn.disconnect())
                return
            asyncio.get_event_loop().create_task(
                self.notify(self.fw, bytes([0x10, 0xD4]) + struct.pack("<I", self.chunk_start)))

    async def control_loop(self, path: str) -> None:
        while True:
            await asyncio.sleep(0.5)
            if not os.path.exists(path):
                continue
            with open(path, encoding="utf-8") as f:
                cmds = [c.strip() for c in f.read().splitlines() if c.strip()]
            os.remove(path)
            for c in cmds:
                log.info("CENÁRIO %s", c)
                if c == "wrongkey":
                    self.wrong_key_next = True
                elif c == "dropmid":
                    self.drop_mid = True
                elif c == "slow":
                    self.slow = not self.slow
                elif c in ("reject", "ignore"):
                    self.call_answer = c


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--address", default="F3:22:33:44:66:06")
    ap.add_argument("--name", default="Mi Smart Band 6")
    ap.add_argument("--key", default="00112233445566778899aabbccddeeff", help="chave de TESTE (16 bytes em hex)")
    ap.add_argument("--chunk", type=int, default=2048)
    ap.add_argument("--mtu", type=int, default=247)
    ap.add_argument("--model", default="mi_band_6", choices=sorted(MODELS))
    ap.add_argument("--transport", default="android-netsim")
    args = ap.parse_args()
    setup_logging("huami")
    band = Band(args)

    async def on_ready(device) -> None:
        band.device = device
        # A Mi Band real negocia MTU 247 (pacote de 244). Sem isto o netsim aceita 517 e o app manda
        # pacotes de 514 bytes, acima do máximo de 512 de um valor de atributo.
        device.gatt_server.max_mtu = args.mtu
        asyncio.get_event_loop().create_task(band.control_loop("control-huami.txt"))

    await run_peripheral(args.name, args.address, band.services(), 0xFEE0, None, on_ready, args.transport)


if __name__ == "__main__":
    asyncio.run(main())
