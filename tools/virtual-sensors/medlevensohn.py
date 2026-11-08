"""
Sensor virtual Smart 2.0 (MedLevensohn) / família Microtech MD (AiDEX X, LinX, Lumi) — fase V2 do plano
`Projeto DM1/GluView/14_simulacao_sensores_virtuais.md`.

Lado do sensor derivado do que o GluView (MedLevensohnProtocol/Session/BleManager) e o Juggluco
(aidexx/*.cpp, AidexXGattCallback.java) esperam:
  - f001: o app escreve MD5(serial*13+61); o sensor responde (notify) com a chave-mestra (16 bytes).
  - f002 (leitura): chave de sessão NOVA a cada leitura = AES-CFB(mestra, IV)(sessão16 + CRC8).
  - f002 (escrita/notify): comandos e respostas cifrados com a sessão, CRC16-CCITT-false no fim;
    resposta = opcode do comando + 0x100 [HIP: o sensor real pode intercalar outras mensagens].
  - f003 (notify): glicemia ao vivo (15 bytes + CRC16), cifrada com a sessão.
  - Pareamento: o sensor pede (Just Works) logo depois de entregar a chave-mestra [HIP].
  - MTU: o sensor pede 247 ao conectar (a 0x110 real tem mais de 20 bytes) — `--mtu 0` desliga [HIP].

Controle por `control-medlev.txt` (um comando por linha):
  val 100 95 · end · badcrc2 · badcrc3 · badkey · notime · laterstart 30 · away 8 · drop · forget ·
  normal
Só dados sintéticos. GPLv3 (como o GluView).
"""
from __future__ import annotations

import argparse
import asyncio
import datetime as dt
import hashlib
import json
import math
import os

from bumble.att import Attribute
from bumble.device import Peer
from bumble.gatt import Characteristic, CharacteristicValue, Service
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms
try:  # cryptography >= 46 moveu o CFB para "decrepit"
    from cryptography.hazmat.decrepit.ciphers.modes import CFB
except ImportError:  # pragma: no cover
    from cryptography.hazmat.primitives.ciphers.modes import CFB

from base import hexs, log, now_utc, run_peripheral, setup_logging

P = Characteristic.Properties


def sig(short: str) -> str:
    return f"0000{short}-0000-1000-8000-00805f9b34fb"


# ── Cripto e CRCs (espelho de MedLevensohnCrypto/MedLevensohnCrc) ────────────────────────────────

def serial_bytes(serial: str) -> bytes:
    def v(c: str) -> int:
        if c.isdigit():
            return ord(c) - 48
        if "A" <= c <= "Z":
            return ord(c) - 0x37
        if "a" <= c <= "z":
            return ord(c) - 0x57
        return ord(c) & 0xFF
    return bytes(v(c) for c in serial)


def derive_iv(serial: str) -> bytes:
    return hashlib.md5(bytes((b * 17 + 0x13) & 0xFF for b in serial_bytes(serial))).digest()


def derive_ask_key(serial: str) -> bytes:
    return hashlib.md5(bytes((b * 13 + 61) & 0xFF for b in serial_bytes(serial))).digest()


def cfb(key: bytes, iv: bytes, data: bytes, encrypt: bool) -> bytes:
    c = Cipher(algorithms.AES(key), CFB(iv))
    op = c.encryptor() if encrypt else c.decryptor()
    return op.update(data) + op.finalize()


def crc8_maxim(data: bytes) -> int:
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0x8C if crc & 1 else crc >> 1
    return crc & 0xFF


def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if crc & 0x8000 else (crc << 1)
            crc &= 0xFFFF
    return crc


def with_crc(payload: bytes) -> bytes:
    c = crc16(payload)
    return payload + bytes([c & 0xFF, c >> 8])


def u16(v: int) -> bytes:
    return bytes([v & 0xFF, (v >> 8) & 0xFF])


def glucose_curve(minute: int) -> float:
    day = minute % 1440
    base = 120 + 25 * math.sin(2 * math.pi * minute / 1440.0)
    meals = sum(70 * math.exp(-((day - m) / 45.0) ** 2) for m in (480, 750, 1170))
    dip = -55 * math.exp(-((day - 200) / 50.0) ** 2)
    return round(base + meals + dip, 1)


class Smart2:
    HISTORY_MINUTES = 15 * 24 * 60

    def __init__(self, serial: str, start_minute: int, fresh: bool, no_time: bool) -> None:
        self.serial = serial
        self.iv = derive_iv(serial)
        self.ask_key = derive_ask_key(serial)
        self.state_file = f"medlev-{serial}-estado.json"
        st = {} if fresh or not os.path.exists(self.state_file) else json.load(open(self.state_file, encoding="utf-8"))
        self.master = bytes.fromhex(st["master"]) if "master" in st else os.urandom(16)
        if "start" in st:
            self.start = dt.datetime.fromisoformat(st["start"]) if st["start"] else None
        else:
            self.start = None if no_time else now_utc() - dt.timedelta(minutes=start_minute)
        self.boot = dt.datetime.fromisoformat(st["boot"]) if "boot" in st else now_utc() - dt.timedelta(minutes=start_minute)
        self.ended = st.get("ended", False)
        self.overrides: dict[int, float] = {}
        self.next_values: list[float] = []
        self.session: bytes | None = None
        self.corrupt_main = self.corrupt_current = self.bad_key = False
        self.mtu_request = 247
        self.ask_pairing = True
        self.page_values = 7
        self.away_until: dt.datetime | None = None
        self.device = None
        self.main_char = self.unbonded_char = self.bonded_char = None
        self.save()

    def save(self) -> None:
        json.dump({"master": self.master.hex(), "start": self.start.isoformat() if self.start else None,
                   "boot": self.boot.isoformat(), "ended": self.ended},
                  open(self.state_file, "w", encoding="utf-8"))

    # Relógio: minutos desde que o sensor "ligou" (sem hora ele conta igual; o início só vem com 0x20).
    def minute_now(self) -> int:
        ref = self.start or self.boot
        return max(0, int((now_utc() - ref).total_seconds() // 60))

    def glucose(self, minute: int) -> int:
        return int(round(self.overrides.get(minute, glucose_curve(minute))))

    # ── Mensagens do sensor ──────────────────────────────────────────────────────────────────
    def device_info(self) -> bytes:
        name = self.serial.encode()[:10].ljust(10, b"\0")
        return bytes([0x10, 0x01]) + u16(0) + bytes([1, 8, 2, 0, 15, 0]) + name  # modelo, versão, 15 dias

    def local_start_time(self) -> bytes:
        if self.start is None:
            return bytes(9)  # ano 0: sensor sem hora
        local = self.start.astimezone()
        off = local.utcoffset() or dt.timedelta(0)
        dst = local.dst() or dt.timedelta(0)
        q = int((off - dst).total_seconds() // 900)
        return (u16(local.year) + bytes([local.month, local.day, local.hour, local.minute, local.second,
                                          q & 0xFF, int(dst.total_seconds() // 900)]))

    def last_past(self, minute: int) -> bytes:
        g = self.glucose(minute)
        trend = int(round((g - self.glucose(minute - 5)) / 5.0 * 10))
        return u16(minute) + bytes([0, 0, trend & 0xFF]) + u16(0x8000 | (g & 0x3FF)) + bytes([92])

    def current(self, minute: int) -> bytes:
        g = self.glucose(minute)
        trend = max(-127, min(127, int(round((g - self.glucose(minute - 5)) / 5.0 * 10))))
        typ = 3 if self.ended else 1
        word = 0x8000 | (g & 0x3FF) | (0x400 if minute < 60 else 0)
        return bytes([typ, 0, 0, trend & 0xFF]) + u16(minute) + u16(word) + bytes(7)

    # ── GATT ─────────────────────────────────────────────────────────────────────────────────
    def services(self) -> list[Service]:
        rw = Attribute.Permissions.READABLE | Attribute.Permissions.WRITEABLE
        self.unbonded_char = Characteristic(sig("f001"), P.WRITE | P.NOTIFY, rw,
                                            CharacteristicValue(write=self.write_f001))
        self.main_char = Characteristic(sig("f002"), P.READ | P.WRITE | P.WRITE_WITHOUT_RESPONSE | P.NOTIFY, rw,
                                        CharacteristicValue(read=self.read_f002, write=self.write_f002))
        self.bonded_char = Characteristic(sig("f003"), P.NOTIFY, rw, b"")
        return [Service(sig("181f"), [self.unbonded_char, self.main_char, self.bonded_char])]

    def write_f001(self, conn, value: bytes) -> None:
        value = bytes(value)
        ok = value == self.ask_key
        log.info("f001 <- pedido de chave (%s)", "confere" if ok else "ERRADO")
        if ok:
            asyncio.get_event_loop().create_task(self._send_master(conn))

    async def _send_master(self, conn) -> None:
        await asyncio.sleep(0.05)
        log.info("f001 -> chave-mestra")
        await self.device.notify_subscribers(self.unbonded_char, self.master)
        if not conn.is_encrypted and self.ask_pairing:
            await asyncio.sleep(0.3)
            log.info("Sensor pede pareamento (Just Works)")
            conn.request_pairing()

    def read_f002(self, conn) -> bytes:
        key = os.urandom(16)
        crc = crc8_maxim(key)
        if self.bad_key:
            crc ^= 0xFF
            self.bad_key = False
            log.info("Cenário badkey: chave de sessão com CRC8 errado")
        self.session = key
        log.info("f002 lida: chave de sessão nova")
        return cfb(self.master, self.iv, key + bytes([crc]), True)

    def write_f002(self, conn, value: bytes) -> None:
        if self.session is None:
            log.info("f002 <- escrita sem sessão (ignorada)")
            return
        plain = cfb(self.session, self.iv, bytes(value), False)
        ok = len(plain) >= 3 and crc16(plain[:-2]) == (plain[-2] | plain[-1] << 8)
        log.info("f002 <- %s (CRC %s)", hexs(plain), "ok" if ok else "ERRADO")
        if ok:
            asyncio.get_event_loop().create_task(self._command(conn, plain[:-2]))

    async def _command(self, conn, cmd: bytes) -> None:
        await asyncio.sleep(0.03)
        op = cmd[0]
        if op == 0x10:
            await self._reply(self.device_info())
        elif op == 0x21:
            await self._reply(bytes([0x21, 0x01]) + self.local_start_time())
        elif op == 0x35:
            await self._reply(bytes([0x35, 0x01, 0x01]))
        elif op == 0x34:
            await self._reply(bytes([0x34, 0x01, 0x01]))
        elif op == 0x11:
            await self._reply(bytes([0x11, 0x01]) + self.last_past(self.minute_now()))
        elif op == 0x22:
            await self._reply(bytes([0x22, 0x01]) + u16(1) + u16(1) + u16(self.minute_now()))
        elif op == 0x23:
            await self._history(cmd[1] | cmd[2] << 8)
        elif op == 0x31:
            await self._reply(bytes([0x31, 0x01]))
        elif op == 0x20:
            if len(cmd) >= 10:
                y = cmd[1] | cmd[2] << 8
                q = cmd[8] - 256 if cmd[8] > 127 else cmd[8]
                tz = dt.timezone(dt.timedelta(minutes=15 * (q + cmd[9])))
                when = dt.datetime(y, cmd[3], cmd[4], cmd[5], cmd[6], cmd[7], tzinfo=tz)
                # O relógio do sensor passa a valer: o início é "agora" menos os minutos já contados.
                self.start = when.astimezone(dt.timezone.utc) - dt.timedelta(minutes=self.minute_now())
                self.save()
                log.info("Hora recebida do app: início do sensor %s", self.start.isoformat())
            await self._reply(bytes([0x20, 0x01]))
        elif op == 0xF2:
            await self._reply(bytes([0xF2, 0x01]))
            log.info("Desparear: o sensor esquece o celular")
            await asyncio.sleep(0.3)
            if self.device.keystore is not None:
                await self.device.keystore.delete_all()
            await self._disconnect_all()
        elif op == 0xF3:
            await self._reply(bytes([0xF3, 0x01]))
        else:
            log.info("Comando desconhecido 0x%02x", op)

    async def _history(self, start_id: int) -> None:
        last = self.minute_now()
        first_kept = max(1, last - self.HISTORY_MINUTES)
        ids = [i for i in range(max(start_id, 1), last + 1)][: self.page_values]
        words = b""
        for i in ids:
            words += u16(0x8000 | (self.glucose(i) & 0x3FF)) if i >= first_kept else u16(0)
        log.info("Histórico: %d valores a partir do ID %d (sensor no %d)", len(ids), start_id, last)
        await self._reply(bytes([0x23, 0x01]) + u16(start_id) + words)

    async def _reply(self, payload: bytes) -> None:
        msg = with_crc(payload)
        if self.corrupt_main:
            msg = msg[:-1] + bytes([msg[-1] ^ 0xFF])
            self.corrupt_main = False
            log.info("Cenário badcrc2: CRC errado nesta resposta")
        log.info("f002 -> %s", hexs(msg))
        await self.device.notify_subscribers(self.main_char, cfb(self.session, self.iv, msg, True))

    async def _disconnect_all(self) -> None:
        for c in list(self.device.connections.values()):
            try:
                await c.disconnect()
            except Exception as e:  # noqa: BLE001
                log.info("Falha ao desconectar: %s", e)

    async def on_connect(self, conn) -> None:
        self.session = None
        if self.mtu_request:
            try:
                await asyncio.sleep(0.2)
                mtu = await Peer(conn).request_mtu(self.mtu_request)
                log.info("MTU negociado pelo sensor: %d", mtu)
            except Exception as e:  # noqa: BLE001
                log.info("MTU não negociado: %s", e)

    # ── Laços ────────────────────────────────────────────────────────────────────────────────
    async def live_loop(self, every_s: int) -> None:
        last = None
        while True:
            await asyncio.sleep(every_s)
            m = self.minute_now()
            if m == last:
                continue
            last = m
            if self.next_values:
                self.overrides[m] = self.next_values.pop(0)
                log.info("Valor fixado no minuto %d: %.0f mg/dL", m, self.overrides[m])
            if self.session is None or self.start is None:
                continue
            if self.away_until and now_utc() < self.away_until:
                continue
            msg = with_crc(self.current(m))
            if self.corrupt_current:
                msg = msg[:-1] + bytes([msg[-1] ^ 0xFF])
                self.corrupt_current = False
                log.info("Cenário badcrc3: CRC errado nesta leitura")
            log.info("f003 -> %s", hexs(msg))
            await self.device.notify_subscribers(self.bonded_char, cfb(self.session, self.iv, msg, True))

    async def control_loop(self, path: str) -> None:
        pos = os.path.getsize(path) if os.path.exists(path) else 0
        while True:
            await asyncio.sleep(1)
            if not os.path.exists(path):
                continue
            with open(path, encoding="utf-8") as f:
                f.seek(pos)
                lines = f.read().splitlines()
                pos = f.tell()
            for line in lines:
                if line.strip():
                    await self.command(line.strip())

    async def command(self, line: str) -> None:
        parts = line.split()
        cmd = parts[0].lower()
        log.info("Controle: %s", line)
        if cmd == "val":
            self.next_values.extend(float(v) for v in parts[1:])
        elif cmd == "end":
            self.ended = True; self.save()
        elif cmd == "badcrc2":
            self.corrupt_main = True
        elif cmd == "badcrc3":
            self.corrupt_current = True
        elif cmd == "badkey":
            self.bad_key = True
            await self._disconnect_all()
        elif cmd == "notime":
            self.start = None; self.save()
        elif cmd == "laterstart":
            minutes = int(parts[1]) if len(parts) > 1 else 30
            if self.start:
                self.start += dt.timedelta(minutes=minutes); self.save()
                log.info("Início do sensor agora %s (IDs %d min menores)", self.start.isoformat(), minutes)
            await self._disconnect_all()
        elif cmd == "away":
            minutes = int(parts[1]) if len(parts) > 1 else 8
            self.away_until = now_utc() + dt.timedelta(minutes=minutes)
            self.device.paused_advertising = True
            await self.device.stop_advertising()
            await self._disconnect_all()
            asyncio.get_event_loop().create_task(self._come_back(minutes))
        elif cmd == "drop":
            await self._disconnect_all()
        elif cmd == "forget":
            if self.device.keystore is not None:
                await self.device.keystore.delete_all()
            await self._disconnect_all()
            log.info("Pareamento esquecido pelo sensor")
        elif cmd == "normal":
            self.ended = False; self.save()
        else:
            log.info("Comando desconhecido: %s", cmd)

    async def _come_back(self, minutes: int) -> None:
        await asyncio.sleep(minutes * 60)
        self.away_until = None
        self.device.paused_advertising = False
        log.info("Sensor de volta ao alcance")
        await self.device.advertise_again()


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="22222A2WC8")
    ap.add_argument("--prefix", default="Smart-")
    ap.add_argument("--start-minute", type=int, default=120, help="idade do sensor ao ligar (min)")
    ap.add_argument("--live-every", type=int, default=60)
    ap.add_argument("--address", default="F0:A1:B2:C3:D4:E6")
    ap.add_argument("--mtu", type=int, default=247, help="MTU que o sensor pede (0 = não pede)")
    ap.add_argument("--page", type=int, default=0, help="valores por página do histórico (0 = cabe no MTU)")
    ap.add_argument("--novo", action="store_true", help="sensor novo: esquece chave/início guardados")
    ap.add_argument("--sem-hora", action="store_true", help="sensor novo que ainda não recebeu a hora")
    ap.add_argument("--sem-pedir-pareamento", action="store_true", help="o sensor não pede pareamento")
    ap.add_argument("--control", default="control-medlev.txt")
    ap.add_argument("--transport", default="android-netsim")
    args = ap.parse_args()

    setup_logging(f"medlev-{args.serial}")
    sensor = Smart2(args.serial, args.start_minute, args.novo, args.sem_hora)
    sensor.mtu_request = args.mtu
    sensor.ask_pairing = not args.sem_pedir_pareamento
    sensor.page_values = args.page or (7 if not args.mtu else 30)
    name = f"{args.prefix}{args.serial}"

    async def on_ready(device) -> None:
        sensor.device = device
        device.on("connection", lambda conn: asyncio.ensure_future(sensor.on_connect(conn)))
        asyncio.get_event_loop().create_task(sensor.live_loop(args.live_every))
        asyncio.get_event_loop().create_task(sensor.control_loop(args.control))
        log.info("Sensor no minuto %d, %s", sensor.minute_now(),
                 "com hora" if sensor.start else "SEM hora (espera o 0x20 do app)")

    await run_peripheral(name, args.address, sensor.services(), 0x181F, None, on_ready, args.transport)


if __name__ == "__main__":
    asyncio.run(main())
