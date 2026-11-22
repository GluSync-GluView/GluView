"""Leitor virtual MiaoMiao / Bubble / Atom (plano 21, F6.4) — Bumble + netsim do emulador.

Simula o leitor acoplado ao Libre (Nordic UART: escreve em 6e400002, notifica em 6e400003) com o
protocolo do xDrip (models/Tomato.java, Bubble.java, Atom.java), que o GluView portou:
  - MiaoMiao/Tomato: [D1 05] (frequência) e [F0] (ler) → pacote 0x28 + tamanho + UID[5..13] +
    bateria[13] + memória (344) + 0x29 + patchInfo (6); `--sem-sensor` responde 0x34; `--novo`
    manda 0x32 (sensor novo) e espera [D3 01] [D1 05] [F0].
  - Bubble/Atom: [00 01 05] → 0x80 (firmware/bateria); app responde ACK [02 01 00 00 00 2B] →
    0xC0 (UID), 0xC1 (patchInfo, offset 5 com firmware ≥ 1,35), 0x82 × N (memória 344 + 8, a partir
    do byte 4); `--sem-sensor` responde 0xBF.

A memória é SINTÉTICA, com as três somas CRC-16 certas (LibreUtils.computeCRC16 do xDrip). Ela não
é uma memória de sensor real: o GluView só grava glicemia se o algoritmo oficial (biblioteca da
Abbott) decodificar — no emulador ele não roda, então o esperado é a recusa com o motivo no log.
Cenários (control-bridge.txt): `crcbad` (próxima memória com CRC errado), `status N` (byte de
estado do sensor), `nosensor`, `drop`.

Só dados sintéticos. GPLv3 (como o GluView).
"""
from __future__ import annotations

import argparse
import asyncio
import struct

from bumble.att import Attribute
from bumble.gatt import Characteristic, CharacteristicValue, Service

from base import hexs, log, run_peripheral, setup_logging

P = Characteristic.Properties
RW = Attribute.Permissions.READABLE | Attribute.Permissions.WRITEABLE
NUS = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
NUS_RX = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"   # app escreve
NUS_TX = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"   # leitor notifica


def _crc_table() -> list[int]:
    t = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ 0x8408 if c & 1 else c >> 1
        t.append(c)
    return t


TABLE = _crc_table()


def crc16(data: bytes, start: int, size: int) -> int:
    """LibreUtils.computeCRC16: sobre [start+2, start+size), resultado com os 16 bits invertidos."""
    crc = 0xFFFF
    for i in range(start + 2, start + size):
        crc = (crc >> 8) ^ TABLE[(crc ^ data[i]) & 0xFF]
    rev = 0
    for _ in range(16):
        rev = (rev << 1) | (crc & 1)
        crc >>= 1
    return rev


def make_fram(minutes: int, status: int, bad_crc: bool = False) -> bytes:
    f = bytearray(344)
    f[4] = status
    f[26] = minutes % 16           # índice da tendência
    f[27] = (minutes // 15) % 32   # índice do histórico
    for i in range(16):            # tendência: valor bruto ~ 120 mg/dL × 8,5
        struct.pack_into("<H", f, 28 + i * 6, 1020 + i)
    for i in range(32):
        struct.pack_into("<H", f, 124 + i * 6, 1000 + i * 3)
    struct.pack_into("<H", f, 316, minutes)        # idade do sensor (min)
    struct.pack_into("<H", f, 326, 14 * 24 * 60)   # vida máxima
    for start, size in ((0, 24), (24, 296), (320, 24)):
        struct.pack_into("<H", f, start, crc16(bytes(f), start, size))
    if bad_crc:
        f[30] ^= 0x5A
    return bytes(f)


class Bridge:
    def __init__(self, args) -> None:
        self.args = args
        self.kind = args.kind
        self.device = None
        self.uid = bytes.fromhex(args.uid)
        self.patch_info = bytes.fromhex(args.patch_info)
        self.minutes = args.minutes
        self.status = 3
        self.crc_bad_next = False
        self.no_sensor = args.sem_sensor

    def services(self) -> list[Service]:
        self.tx = Characteristic(NUS_TX, P.NOTIFY, RW, b"")
        self.rx = Characteristic(NUS_RX, P.WRITE | P.WRITE_WITHOUT_RESPONSE, RW, CharacteristicValue(write=self.on_write))
        return [Service(NUS, [self.rx, self.tx])]

    async def notify(self, value: bytes) -> None:
        await self.device.notify_subscribers(self.tx, value)
        await asyncio.sleep(0.015)

    def on_write(self, _conn, value: bytes) -> None:
        log.info("APP -> %s", hexs(bytes(value)))
        asyncio.get_event_loop().create_task(self.handle(bytes(value)))

    async def handle(self, cmd: bytes) -> None:
        await asyncio.sleep(0.05)
        if self.kind == "miaomiao":
            if cmd == b"\xf0":
                await self.send_miaomiao()
            return
        # Bubble / Atom
        if cmd[:1] == b"\x00":                       # início: info do leitor
            if self.no_sensor:
                await self.notify(bytes([0xBF]) + bytes(5))
                log.info("Sem sensor (0xBF)")
                return
            await self.notify(bytes([0x80, 0x01, 0x02, 0x08, 85, 0x00]))   # fw 2.8, bateria 85 %
        elif cmd[:1] in (b"\x02", b"\x08"):           # ACK do app → manda UID, patchInfo e memória
            await self.send_bubble_atom()

    async def send_miaomiao(self) -> None:
        if self.no_sensor:
            await self.notify(bytes([0x34]))
            log.info("Sem sensor (0x34)")
            return
        fram = make_fram(self.minutes, self.status, self.crc_bad_next)
        self.crc_bad_next = False
        total = 18 + 344 + 1
        header = bytearray(18)
        header[0] = 0x28
        header[1], header[2] = total >> 8, total & 0xFF
        header[5:13] = self.uid
        header[13] = 77                                # bateria
        packet = bytes(header) + fram + b"\x29" + self.patch_info
        for off in range(0, len(packet), 20):
            await self.notify(packet[off:off + 20])
        log.info("MiaoMiao: memória enviada (%d bytes, minuto %d)", len(packet), self.minutes)
        self.minutes += 5

    async def send_bubble_atom(self) -> None:
        await self.notify(bytes([0xC0, 0x08]) + self.uid)
        await self.notify(bytes([0xC1, 0x06, 0x00, 0x00, 0x00]) + self.patch_info)
        fram = make_fram(self.minutes, self.status, self.crc_bad_next) + bytes(8)
        self.crc_bad_next = False
        for off in range(0, len(fram), 16):
            await self.notify(bytes([0x82, off // 16, 0x00, 0x00]) + fram[off:off + 16])
        log.info("%s: memória enviada (minuto %d)", self.kind, self.minutes)
        self.minutes += 5

    async def control_loop(self, path: str) -> None:
        open(path, "a").close()
        while True:
            await asyncio.sleep(1)
            lines = open(path, encoding="utf-8").read().splitlines()
            if not lines:
                continue
            open(path, "w").close()
            for line in lines:
                p = line.split()
                if not p:
                    continue
                log.info("Cenário: %s", line)
                if p[0] == "crcbad":
                    self.crc_bad_next = True
                elif p[0] == "status" and len(p) > 1:
                    self.status = int(p[1])
                elif p[0] == "nosensor":
                    self.no_sensor = True
                elif p[0] == "sensor":
                    self.no_sensor = False
                elif p[0] == "drop":
                    for c in list(self.device.connections.values()):
                        await c.disconnect()


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--kind", choices=["miaomiao", "bubble", "atom"], default="miaomiao")
    ap.add_argument("--address", default="F3:22:33:44:55:01")
    ap.add_argument("--uid", default="78563412" "00a007e0")
    ap.add_argument("--patch-info", default="9d0830017136")
    ap.add_argument("--minutes", type=int, default=3000)
    ap.add_argument("--sem-sensor", action="store_true")
    ap.add_argument("--transport", default="android-netsim")
    args = ap.parse_args()
    setup_logging(f"bridge-{args.kind}")
    names = {"miaomiao": "miaomiao2_SIM", "bubble": "Bubble_SIM", "atom": "atom_SIM"}
    bridge = Bridge(args)

    async def on_ready(device) -> None:
        bridge.device = device
        asyncio.get_event_loop().create_task(bridge.control_loop("control-bridge.txt"))

    await run_peripheral(names[args.kind], args.address, bridge.services(), 0xFFE0, None, on_ready, args.transport)


if __name__ == "__main__":
    asyncio.run(main())
