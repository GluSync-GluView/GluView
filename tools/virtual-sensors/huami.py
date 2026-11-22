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


class Band:
    def __init__(self, args) -> None:
        self.key = bytes.fromhex(args.key)
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
        return [Service(SVC_MIBAND, [self.config, self.event]),
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

    def on_config(self, _conn, value: bytes) -> None:
        log.info("CONFIG <- %s", hexs(bytes(value)))

    # ---- envio do visor
    def on_fw(self, _conn, value: bytes) -> None:
        v = bytes(value)
        log.info("FW <- %s", hexs(v))
        asyncio.get_event_loop().create_task(self.handle_fw(v))

    async def handle_fw(self, v: bytes) -> None:
        await asyncio.sleep(0.02)
        if not self.authed:
            log.info("Comando de firmware SEM autenticação: ignorado")
            return
        op = v[0]
        if op == 0xD0:
            await self.notify(self.fw, bytes([0x10, 0xD0, 0x01, 0x00]) + struct.pack("<H", self.chunk_len))
        elif op == 0xD2:
            kind = v[1]
            self.expected_size, self.expected_crc = struct.unpack_from("<II", v, 2)
            chunk = struct.unpack_from("<H", v, 10)[0]
            log.info("FW_INFO tipo=%d tamanho=%d crc32=%08x chunk=%d", kind, self.expected_size, self.expected_crc, chunk)
            await self.notify(self.fw, bytes([0x10, 0xD2, 0x01]))
        elif op == 0xD3:
            self.buf = bytearray()
            self.chunk_start = 0
            self.transferring = True
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
            head = bytes(self.buf[:8])
            kind = ("UIHH (Mi Band 4/5/6)" if head.startswith(b"UIHH") else
                    "HMDIAL (Amazfit)" if head.startswith(b"HMDIAL") else "cabeçalho desconhecido")
            log.info("FINALIZE: visor instalado (%d bytes, cabeçalho %s, %s) -> %s", len(self.buf), hexs(head), kind, name)
            self.transferring = False
            await self.notify(self.fw, bytes([0x10, 0xD6, 0x01]))

    def on_data(self, _conn, value: bytes) -> None:
        if not self.transferring:
            log.info("DADOS fora da transferência (%d bytes): ignorados", len(value))
            return
        self.buf.extend(bytes(value))
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


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--address", default="F3:22:33:44:66:06")
    ap.add_argument("--name", default="Mi Smart Band 6")
    ap.add_argument("--key", default="00112233445566778899aabbccddeeff", help="chave de TESTE (16 bytes em hex)")
    ap.add_argument("--chunk", type=int, default=2048)
    ap.add_argument("--mtu", type=int, default=247)
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
