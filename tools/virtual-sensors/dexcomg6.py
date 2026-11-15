"""Transmissor Dexcom G6 virtual (plano 14_, Fase V3b) — Bumble + netsim do emulador.

Protocolo do xDrip (`g5model`, Ob1G5StateMachine), o mesmo que o GluView porta:
  - anuncia "Dexcom" + 2 últimos do Transmitter ID com o serviço 0xFEBC, só no começo de cada ciclo
    (a cada --cycle s; 300 = como o real) e por --window s; fora disso fica calado;
  - Authentication (indicação): 0x01 token+slot -> 0x03 tokenHash+desafio; 0x04 hash -> 0x05 status;
    0x06 keep-alive; 0x07 pedido de pareamento (aqui marca "pareado": o SMP do Bumble no netsim não
    funciona, então o pareamento do Android não acontece de verdade — limitação anotada no plano);
  - Control (indicação): 0x30 -> 0x31 glicemia; 0x24 -> 0x25 hora; 0x50 -> 0x51 + dados por
    notificação em Backfill; 0x09 -> desconecta.
A chave é ASCII("00"+ID+"00"+ID); o app com o ID errado não confere o tokenHash.

Leituras a cada 5 min desde o início da sessão (curva de glicemia igual à do Smart 2.0). O transmissor
guarda tudo da sessão para o backfill.

Comandos no arquivo de controle (uma linha por comando):
  away N      some por N minutos (não anuncia; o app fica com furo e pede backfill na volta)
  end         sessão encerrada (estado 0x0F nas próximas leituras)
  fail        sensor com falha (estado 0x0B)
  newsession  sessão nova agora (aquecimento de 2 h, estado 0x02)
  nosession   sem sessão (estado 0x01; hora sem início)
  badcrc      a próxima glicemia sai com CRC errado
  unbond      esquece o pareamento (o app refaz KeepAlive + BondRequest)
  normal      volta a sessão em andamento (estado 0x06)
"""
from __future__ import annotations

import argparse
import asyncio
import datetime as dt
import json
import math
import os
import struct

from bumble.att import Attribute
from bumble.gatt import Characteristic, CharacteristicValue, Service
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from base import hexs, log, now_utc, run_peripheral, setup_logging

P = Characteristic.Properties


def uuid(short: str) -> str:
    return f"F808{short}-849E-531C-C594-30F1F86A4EA5"


def crc16(data: bytes) -> bytes:
    crc = 0
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if crc & 0x8000 else (crc << 1)
            crc &= 0xFFFF
    return bytes([crc & 0xFF, crc >> 8])


def with_crc(data: bytes) -> bytes:
    return data + crc16(data)


def challenge_hash(key: bytes, data8: bytes) -> bytes:
    enc = Cipher(algorithms.AES(key), modes.ECB()).encryptor()
    return (enc.update(data8 + data8) + enc.finalize())[:8]


def glucose_curve(minute: int) -> float:
    day = minute % 1440
    base = 120 + 25 * math.sin(2 * math.pi * minute / 1440.0)
    meals = sum(70 * math.exp(-((day - m) / 45.0) ** 2) for m in (480, 750, 1170))
    dip = -55 * math.exp(-((day - 200) / 50.0) ** 2)
    return round(base + meals + dip, 1)


class G6:
    WARMUP_S = 2 * 3600
    SESSION_S = 10 * 86400

    def __init__(self, tx_id: str, session_hours: float, fresh: bool, bonded: bool) -> None:
        self.tx_id = tx_id.upper()
        self.key = f"00{self.tx_id}00{self.tx_id}".encode("ascii")
        self.state_file = f"dexcom-{self.tx_id}-estado.json"
        st = {} if fresh or not os.path.exists(self.state_file) else json.load(open(self.state_file, encoding="utf-8"))
        now = now_utc()
        # Relógio do transmissor: segundos desde a ativação (aqui, 40 dias atrás).
        self.tx_start = dt.datetime.fromisoformat(st["tx_start"]) if "tx_start" in st else now - dt.timedelta(days=40)
        if "session_start" in st:
            self.session_start = dt.datetime.fromisoformat(st["session_start"]) if st["session_start"] else None
        else:
            self.session_start = now - dt.timedelta(hours=session_hours)
        self.bonded = st.get("bonded", bonded)
        self.forced_state: int | None = st.get("forced_state")
        self.token = self.challenge = None
        self.authenticated = False
        self.bad_crc = False
        self.away_until: dt.datetime | None = None
        self.device = None
        self.auth_char = self.control_char = self.backfill_char = self.comm_char = None
        self.save()

    def save(self) -> None:
        json.dump({"tx_start": self.tx_start.isoformat(),
                   "session_start": self.session_start.isoformat() if self.session_start else None,
                   "bonded": self.bonded, "forced_state": self.forced_state},
                  open(self.state_file, "w", encoding="utf-8"))

    # ── Relógio e leituras ──────────────────────────────────────────────────────────────────
    def dex_now(self) -> int:
        return int((now_utc() - self.tx_start).total_seconds())

    def session_dex(self) -> int | None:
        return int((self.session_start - self.tx_start).total_seconds()) if self.session_start else None

    def state_at(self, dex: int) -> int:
        s = self.session_dex()
        if self.forced_state is not None:
            return self.forced_state
        if s is None:
            return 0x01
        age = dex - s
        if age < self.WARMUP_S:
            return 0x02
        if age > self.SESSION_S:
            return 0x0F
        return 0x06

    def readings(self, from_dex: int, to_dex: int) -> list[tuple[int, int, int, int]]:
        """(dexTime, mg/dL, estado, tendência) das leituras de 5 em 5 min da sessão dentro da janela."""
        s = self.session_dex()
        if s is None:
            return []
        out = []
        first = s + 300 * max(1, math.ceil((from_dex - s) / 300))
        t = first
        while t <= min(to_dex, self.dex_now()):
            minute = (t - s) // 60
            g = glucose_curve(minute)
            trend = max(-127, min(126, int(round((g - glucose_curve(minute - 5)) / 5.0 * 10))))
            state = self.state_at(t)
            out.append((t, int(g) if state in (0x06, 0x07) else 0, state, trend))
            t += 300
        return out

    def latest(self) -> tuple[int, int, int, int]:
        r = self.readings(self.dex_now() - 300, self.dex_now())
        if r:
            return r[-1]
        return (self.dex_now(), 0, self.state_at(self.dex_now()), 127)

    # ── GATT ────────────────────────────────────────────────────────────────────────────────
    def services(self) -> list[Service]:
        rw = Attribute.Permissions.READABLE | Attribute.Permissions.WRITEABLE
        self.comm_char = Characteristic(uuid("3533"), P.READ | P.NOTIFY, rw, b"")
        self.control_char = Characteristic(uuid("3534"), P.WRITE | P.INDICATE, rw,
                                           CharacteristicValue(write=self.write_control))
        self.auth_char = Characteristic(uuid("3535"), P.READ | P.WRITE | P.INDICATE, rw,
                                        CharacteristicValue(read=self.read_auth, write=self.write_auth))
        self.backfill_char = Characteristic(uuid("3536"), P.READ | P.NOTIFY, rw, b"")
        return [Service(uuid("3532"), [self.comm_char, self.control_char, self.auth_char, self.backfill_char])]

    def status_packet(self) -> bytes:
        return bytes([0x05, 1 if self.authenticated else 2, 1 if self.bonded else 2])

    def read_auth(self, conn) -> bytes:
        log.info("Authentication lida -> %s", hexs(self.status_packet()))
        return self.status_packet()

    def write_auth(self, conn, value: bytes) -> None:
        value = bytes(value)
        op = value[0] if value else -1
        loop = asyncio.get_event_loop()
        if op == 0x01 and len(value) >= 9:
            self.token = value[1:9]
            self.challenge = os.urandom(8)
            log.info("AuthRequest (slot %d)", value[9] if len(value) > 9 else -1)
            loop.create_task(self.indicate(self.auth_char, bytes([0x03]) + challenge_hash(self.key, self.token) + self.challenge))
        elif op == 0x04 and len(value) >= 9 and self.challenge:
            self.authenticated = value[1:9] == challenge_hash(self.key, self.challenge)
            log.info("Resposta ao desafio: %s", "confere" if self.authenticated else "ERRADA")
            loop.create_task(self.indicate(self.auth_char, self.status_packet()))
        elif op == 0x06:
            log.info("KeepAlive %ds", value[1] if len(value) > 1 else 0)
        elif op == 0x07:
            self.bonded = True
            self.save()
            log.info("BondRequest: transmissor passa a considerar o celular pareado")
        else:
            log.info("Authentication <- %s (ignorado)", hexs(value))

    def write_control(self, conn, value: bytes) -> None:
        value = bytes(value)
        if not (self.authenticated and self.bonded):
            log.info("Control sem autenticação/pareamento: %s (ignorado)", hexs(value))
            return
        op = value[0] if value else -1
        loop = asyncio.get_event_loop()
        if op == 0x30:
            dex, mg, state, trend = self.latest()
            seq = (dex // 300) & 0xFFFFFFFF
            msg = with_crc(bytes([0x31, 0x00]) + struct.pack("<IIH", seq, dex, mg & 0x0FFF) + bytes([state, trend & 0xFF]))
            if self.bad_crc:
                msg = msg[:-1] + bytes([msg[-1] ^ 0xFF])
                self.bad_crc = False
                log.info("Cenário badcrc: CRC errado nesta glicemia")
            log.info("Glicemia -> %d mg/dL estado 0x%02X (dex %d)", mg, state, dex)
            loop.create_task(self.indicate(self.control_char, msg))
        elif op == 0x24:
            s = self.session_dex()
            msg = with_crc(bytes([0x25, 0x00]) + struct.pack("<ii", self.dex_now(), s if s is not None else -1))
            log.info("Hora -> dex %d, sessão %s", self.dex_now(), s)
            loop.create_task(self.indicate(self.control_char, msg))
        elif op == 0x50 and len(value) >= 12:
            start, end = struct.unpack("<II", value[4:12])
            loop.create_task(self.send_backfill(start, end))
        elif op == 0x09:
            log.info("Disconnect pedido pelo app")
            loop.create_task(self._disconnect_all())
        else:
            log.info("Control <- %s (ignorado)", hexs(value))

    async def indicate(self, char, value: bytes) -> None:
        await asyncio.sleep(0.05)
        await self.device.indicate_subscribers(char, value)

    async def send_backfill(self, start: int, end: int) -> None:
        recs = self.readings(start, end)
        log.info("Backfill pedido de dex %d a %d: %d leituras", start, end, len(recs))
        await self.indicate(self.control_char, with_crc(bytes([0x51, 0x00, 0x01, 0x00]) + struct.pack("<II", start, end) + bytes(6)))
        data = struct.pack("<I", len(recs) * 8) + b"".join(
            struct.pack("<IHBb", t, mg & 0x0FFF, st, max(-128, min(127, tr))) for t, mg, st, tr in recs)
        seq = 1
        for i in range(0, len(data), 18):
            await asyncio.sleep(0.03)
            await self.device.notify_subscribers(self.backfill_char, bytes([seq, 0]) + data[i:i + 18])
            seq += 1

    async def _disconnect_all(self) -> None:
        for c in list(self.device.connections.values()):
            try:
                await c.disconnect()
            except Exception as e:  # noqa: BLE001
                log.info("Falha ao desconectar: %s", e)

    # ── Ciclo de 5 min: anuncia só na janela ─────────────────────────────────────────────────
    async def cycle_loop(self, every_s: int, window_s: int) -> None:
        d = self.device
        while True:
            if self.away_until and now_utc() < self.away_until:
                await asyncio.sleep(5)
                continue
            self.away_until = None
            self.authenticated = False
            d.paused_advertising = False
            await d.advertise_again()
            log.info("Ciclo: anunciando por %ds", window_s)
            await asyncio.sleep(window_s)
            d.paused_advertising = True
            if d.is_advertising:
                await d.stop_advertising()
                log.info("Ciclo: ninguém conectou nesta janela")
            await asyncio.sleep(max(1, every_s - window_s))

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
        if cmd == "away":
            minutes = int(parts[1]) if len(parts) > 1 else 15
            self.away_until = now_utc() + dt.timedelta(minutes=minutes)
            self.device.paused_advertising = True
            if self.device.is_advertising:
                await self.device.stop_advertising()
            await self._disconnect_all()
        elif cmd == "end":
            self.forced_state = 0x0F
        elif cmd == "fail":
            self.forced_state = 0x0B
        elif cmd == "newsession":
            self.forced_state = None
            self.session_start = now_utc()
        elif cmd == "nosession":
            self.forced_state = None
            self.session_start = None
        elif cmd == "badcrc":
            self.bad_crc = True
        elif cmd == "unbond":
            self.bonded = False
        elif cmd == "normal":
            self.forced_state = None
            if self.session_start is None or (now_utc() - self.session_start).total_seconds() < self.WARMUP_S:
                self.session_start = now_utc() - dt.timedelta(days=3)
        else:
            log.info("Comando desconhecido: %s", cmd)
        self.save()


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--id", default="8G1A2B", help="Transmitter ID verdadeiro do transmissor virtual")
    ap.add_argument("--address", default="F0:A1:B2:C3:D4:A6")
    ap.add_argument("--session-hours", type=float, default=72, help="idade da sessão ao ligar (h)")
    ap.add_argument("--cycle", type=int, default=300, help="segundos entre anúncios (300 = real)")
    ap.add_argument("--window", type=int, default=20, help="segundos anunciando em cada ciclo")
    ap.add_argument("--novo", action="store_true", help="esquece o estado guardado")
    ap.add_argument("--pareado", action="store_true", help="já começa pareado com o celular")
    ap.add_argument("--control", default="control-dexcom.txt")
    ap.add_argument("--transport", default="android-netsim")
    args = ap.parse_args()

    setup_logging(f"dexcom-{args.id}")
    tx = G6(args.id, args.session_hours, args.novo, args.pareado)
    name = "Dexcom" + args.id.upper()[-2:]

    async def on_ready(device) -> None:
        tx.device = device
        device.paused_advertising = True  # o ciclo decide quando anunciar
        if device.is_advertising:
            await device.stop_advertising()
        asyncio.get_event_loop().create_task(tx.cycle_loop(args.cycle, args.window))
        asyncio.get_event_loop().create_task(tx.control_loop(args.control))
        s = tx.session_dex()
        log.info("Transmissor %s (%s): dex %d, sessão %s, pareado=%s", tx.tx_id, name, tx.dex_now(),
                 "nenhuma" if s is None else f"há {(tx.dex_now() - s) / 3600:.1f} h", tx.bonded)

    await run_peripheral(name, args.address, tx.services(), 0xFEBC, None, on_ready, args.transport)


if __name__ == "__main__":
    asyncio.run(main())
