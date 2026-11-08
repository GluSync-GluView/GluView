"""
Accu-Chek SmartGuide virtual (fase V1 de `Projeto DM1/GluView/14_simulacao_sensores_virtuais.md`).

Imita o sensor como o GluView o entende (serviço CGM 0x181F da Bluetooth SIG, ver
`app/.../data/accuchek/cgm/` e o plano 12_): nome `AC-<serial>`, PIN de 6 dígitos (passkey),
Feature com E2E-CRC/tendência/qualidade, Status, Session Start/Run Time, leituras (flags 0x43 como o
real), RACP (histórico das últimas 8 h, filtro por minuto) e SOCP (intervalo, calibração), sempre com
CRC-16/MCRF4XX. Dados 100% sintéticos (curva determinística).

Uso (no PowerShell, a partir desta pasta, com o emulador ligado):
    .\\.venv\\Scripts\\python.exe smartguide.py [--pin 123456] [--serial 1R009999990]
        [--start-minute 4762] [--live-every 60]

Cenários durante a execução: escreva uma linha em `control.txt` (na mesma pasta), ex.:
    val 90 85, lo, hi, error, temp, battery, calib, end, crcbad, multi, away 40, drop, forget, refuse, accept, restart, normal
"""
from __future__ import annotations

import argparse
import asyncio
import datetime as dt
import math
import os

from bumble.att import Attribute
from bumble.gatt import Characteristic, CharacteristicValue, Service

from base import hexs, log, now_utc, run_peripheral, setup_logging

# ── Codificação ──────────────────────────────────────────────────────────────────────────────


def crc16_mcrf4xx(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0x8408 if crc & 1 else crc >> 1
    return crc & 0xFFFF


def with_crc(payload: bytes) -> bytes:
    crc = crc16_mcrf4xx(payload)
    return payload + bytes([crc & 0xFF, crc >> 8])


def crc_ok(data: bytes) -> bool:
    return len(data) >= 3 and crc16_mcrf4xx(data[:-2]) == (data[-2] | data[-1] << 8)


SF_NAN, SF_HI, SF_LO = 0x07FF, 0x07FE, 0x0802


def sfloat(value: float | int | None, decimals: int = 1) -> int:
    """SFLOAT com expoente −decimals (ou 0); None = NaN."""
    if value is None:
        return SF_NAN
    mantissa = round(value * (10 ** decimals))
    exponent = -decimals
    while not -2045 <= mantissa <= 2045:
        mantissa //= 10
        exponent += 1
    return ((exponent & 0xF) << 12) | (mantissa & 0x0FFF)


def u16(v: int) -> bytes:
    return bytes([v & 0xFF, (v >> 8) & 0xFF])


# ── Sensor ───────────────────────────────────────────────────────────────────────────────────


def glucose_curve(minute: int) -> float:
    """Curva sintética determinística (mg/dL): base ~120, três refeições, queda de madrugada."""
    day = minute % 1440
    base = 120 + 25 * math.sin(2 * math.pi * minute / 1440.0)
    meals = sum(70 * math.exp(-((day - m) / 45.0) ** 2) for m in (480, 750, 1170))
    dip = -55 * math.exp(-((day - 200) / 50.0) ** 2)
    return round(base + meals + dip, 1)


class SmartGuide:
    HISTORY_MINUTES = 480  # o sensor guarda 8 h

    def __init__(self, serial: str, start_minute: int) -> None:
        self.serial = serial
        self.start = now_utc() - dt.timedelta(minutes=start_minute)
        self.mode = "normal"          # efeito aplicado às próximas leituras ao vivo
        self.mode_left = 0
        self.session_ended = False
        self.racp_multi = False
        self.overrides: dict[int, float] = {}  # minuto → valor fixado pelo comando "val"
        self.next_values: list[float] = []
        self.records: dict[int, bytes] = {}  # minuto → registro (o que o RACP devolve)
        self.away_until: dt.datetime | None = None
        self.measurement_char: Characteristic | None = None
        self.racp_char: Characteristic | None = None
        self.socp_char: Characteristic | None = None
        self.device = None
        self._seed_history()

    # Relógio do sensor --------------------------------------------------------------------
    def minute_now(self) -> int:
        return int((now_utc() - self.start).total_seconds() // 60)

    def _seed_history(self) -> None:
        cur = self.minute_now()
        first = max(0, cur - self.HISTORY_MINUTES)
        for m in range(first - first % 5, cur + 1, 5):
            if m >= 0:
                self.records[m] = self.record(m)

    # Registros ----------------------------------------------------------------------------
    def record(self, minute: int, mode: str = "normal") -> bytes:
        g = self.overrides.get(minute, glucose_curve(minute))
        trend = (g - self.overrides.get(minute - 5, glucose_curve(minute - 5))) / 5.0
        status, cal_temp, warning = 0x00, 0x02, 0x00
        glucose = sfloat(g)
        if mode == "lo":
            glucose, status, warning = SF_LO, 0x08, 0x40
        elif mode == "hi":
            glucose, status, warning = SF_HI, 0x08, 0x80
        elif mode == "error":
            glucose, status = SF_NAN, 0x08
        elif mode == "temp":
            cal_temp |= 0x10
        elif mode == "battery":
            status |= 0x02
        elif mode == "calib":
            cal_temp |= 0x08
        if self.session_ended:
            status |= 0x01
        flags = 0x03 | 0x40  # tendência + qualidade + octeto Cal/Temp (como o real)
        octets = bytes([cal_temp])
        if status or warning:
            # Ordem: Status, Cal/Temp, Warning. O registro de erro real (Juggluco) não traz o Warning.
            flags |= 0x80
            octets = bytes([status, cal_temp])
            if warning:
                flags |= 0x20
                octets += bytes([warning])
        trend_sf = SF_NAN if mode == "error" else sfloat(trend, 2)
        quality_sf = SF_NAN if mode == "error" else sfloat(92, 0)
        body = bytes([flags]) + u16(glucose) + u16(minute) + octets + u16(trend_sf) + u16(quality_sf)
        rec = with_crc(bytes([len(body) + 3]) + body)
        assert rec[0] == len(rec)
        return rec

    def annunciation(self) -> bytes:
        status = 0x01 if self.session_ended else 0x00
        return bytes([status, 0x02, 0x00])

    # Características ----------------------------------------------------------------------
    def read_feature(self, _conn) -> bytes:
        bits = 0x019BC1  # calibração, defeito, temperatura, faixa, bateria, tipo, falha, E2E-CRC, tendência, qualidade
        return with_crc(bytes([bits & 0xFF, (bits >> 8) & 0xFF, bits >> 16, 0x59]))

    def read_status(self, _conn) -> bytes:
        value = with_crc(u16(self.minute_now()) + self.annunciation())
        log.info("Leitura do Status: minuto %d", self.minute_now())
        return value

    def read_start_time(self, _conn) -> bytes:
        s = self.start
        return with_crc(u16(s.year) + bytes([s.month, s.day, s.hour, s.minute, s.second, 0x00, 0xFF]))

    def write_start_time(self, _conn, value: bytes) -> None:
        log.info("App escreveu Session Start Time: %s (ignorado)", hexs(value))

    def read_run_time(self, _conn) -> bytes:
        return with_crc(u16(336))

    def write_racp(self, _conn, value: bytes) -> None:
        log.info("RACP <- %s", hexs(value))
        asyncio.get_event_loop().create_task(self._racp(bytes(value)))

    async def _racp(self, cmd: bytes) -> None:
        await asyncio.sleep(0.05)
        if len(cmd) < 2 or cmd[0] not in (0x01, 0x04):
            await self._indicate(self.racp_char, bytes([0x06, 0x00, cmd[0] if cmd else 0, 0x02]))
            return
        minutes = sorted(self.records)
        op, operator = cmd[0], cmd[1]
        if operator == 0x01:
            chosen = minutes
        elif operator == 0x03 and len(cmd) >= 5:
            chosen = [m for m in minutes if m >= cmd[3] | cmd[4] << 8]
        elif operator == 0x04 and len(cmd) >= 7:
            lo, hi = cmd[3] | cmd[4] << 8, cmd[5] | cmd[6] << 8
            chosen = [m for m in minutes if lo <= m <= hi]
        elif operator == 0x06:
            chosen = minutes[-1:]
        else:
            await self._indicate(self.racp_char, bytes([0x06, 0x00, op, 0x04]))
            return
        if op == 0x04:
            await self._indicate(self.racp_char, bytes([0x05, 0x00]) + u16(len(chosen)))
            return
        log.info("RACP: enviando %d registros", len(chosen))
        # Dois registros por notificação só cabem com MTU negociado acima do padrão (23 → 20 bytes úteis);
        # o SmartGuide real (e o Juggluco) trabalham com um registro por notificação.
        mtu = min((c.att_mtu for c in self.device.connections.values()), default=23)
        batch = self.racp_multi and mtu - 3 >= 26
        if self.racp_multi and not batch:
            log.info("multi ignorado: MTU %d não comporta 2 registros", mtu)
        i = 0
        while i < len(chosen):
            if batch and i + 1 < len(chosen):
                await self._notify(self.records[chosen[i]] + self.records[chosen[i + 1]]); i += 2
            else:
                await self._notify(self.records[chosen[i]]); i += 1
            await asyncio.sleep(0.02)
        code = 0x01 if chosen else 0x06
        await self._indicate(self.racp_char, bytes([0x06, 0x00, 0x01, code]))

    def write_socp(self, _conn, value: bytes) -> None:
        cmd = bytes(value)
        log.info("SOCP <- %s (CRC %s)", hexs(cmd), "ok" if crc_ok(cmd) else "ERRADO")
        asyncio.get_event_loop().create_task(self._socp(cmd))

    async def _socp(self, cmd: bytes) -> None:
        await asyncio.sleep(0.05)
        if not crc_ok(cmd):
            return  # o real ignora comando com CRC errado
        op = cmd[0]
        if op == 0x02:
            resp = bytes([0x03, 0x05])
        elif op == 0x05:
            resp = bytes([0x06]) + u16(sfloat(100, 0)) + u16(300) + bytes([0x11]) + u16(1740) + u16(2) + bytes([0])
        elif op == 0x01:
            resp = bytes([0x1C, 0x01, 0x01])
        else:
            resp = bytes([0x1C, op, 0x02])
        await self._indicate(self.socp_char, with_crc(resp))

    async def _notify(self, value: bytes) -> None:
        log.info("Medição -> %s", hexs(value))
        await self.device.notify_subscribers(self.measurement_char, value)

    async def _indicate(self, char: Characteristic, value: bytes) -> None:
        log.info("Indicação %s -> %s", char.uuid, hexs(value))
        await self.device.indicate_subscribers(char, value)

    # Serviços -----------------------------------------------------------------------------
    def services(self) -> list[Service]:
        enc_r = Attribute.Permissions.READABLE | Attribute.Permissions.READ_REQUIRES_ENCRYPTION
        enc_rw = enc_r | Attribute.Permissions.WRITEABLE | Attribute.Permissions.WRITE_REQUIRES_ENCRYPTION
        P = Characteristic.Properties
        self.measurement_char = Characteristic("2AA7", P.NOTIFY, enc_r, b"")
        self.racp_char = Characteristic("2A52", P.WRITE | P.INDICATE, enc_rw, CharacteristicValue(write=self.write_racp))
        self.socp_char = Characteristic("2AAC", P.WRITE | P.INDICATE, enc_rw, CharacteristicValue(write=self.write_socp))
        cgm = Service("181F", [
            self.measurement_char,
            Characteristic("2AA8", P.READ, enc_r, CharacteristicValue(read=self.read_feature)),
            Characteristic("2AA9", P.READ, enc_r, CharacteristicValue(read=self.read_status)),
            Characteristic("2AAA", P.READ | P.WRITE, enc_rw,
                           CharacteristicValue(read=self.read_start_time, write=self.write_start_time)),
            Characteristic("2AAB", P.READ, enc_r, CharacteristicValue(read=self.read_run_time)),
            self.racp_char,
            self.socp_char,
        ])
        r = Attribute.Permissions.READABLE
        dis = Service("180A", [
            Characteristic("2A29", P.READ, r, b"Roche"),
            Characteristic("2A24", P.READ, r, b"SmartGuide-Virtual"),
            Characteristic("2A25", P.READ, r, self.serial.encode()),
            Characteristic("2A26", P.READ, r, b"01.00.00-sim"),
            Characteristic("2A27", P.READ, r, b"sim"),
        ])
        return [cgm, dis]

    # Ciclo ao vivo e controle ---------------------------------------------------------------
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
            mode = self.mode if self.mode_left > 0 else "normal"
            rec = self.record(m, mode)
            if self.mode == "crcbad" and self.mode_left > 0:
                rec = rec[:-1] + bytes([rec[-1] ^ 0xFF])
            if self.mode_left > 0:
                self.mode_left -= 1
                if self.mode_left == 0:
                    log.info("Cenário %s terminou", self.mode); self.mode = "normal"
            if mode not in ("crcbad",):
                self.records[m] = self.record(m, mode)
            for old in [k for k in self.records if k < m - self.HISTORY_MINUTES]:
                del self.records[old]
            if self.away_until and now_utc() < self.away_until:
                continue
            await self._notify(rec)

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
                await self.command(line.strip())

    async def command(self, line: str) -> None:
        if not line:
            return
        parts = line.split()
        cmd = parts[0].lower()
        log.info("Controle: %s", line)
        if cmd == "val":
            # "val 90 85" = as próximas leituras ao vivo valem 90 e depois 85 mg/dL.
            self.next_values.extend(float(v) for v in parts[1:])
        elif cmd == "multi":
            # Vale para o próximo histórico (RACP) até "normal": 2 registros por notificação.
            self.racp_multi = True
        elif cmd in ("lo", "hi", "temp", "battery", "calib", "crcbad"):
            self.mode, self.mode_left = cmd, 1
        elif cmd == "error":
            self.mode, self.mode_left = "error", int(parts[1]) if len(parts) > 1 else 3
        elif cmd == "end":
            self.session_ended = True
        elif cmd == "normal":
            self.mode, self.mode_left, self.session_ended = "normal", 0, False
            self.racp_multi = False
        elif cmd == "away":
            minutes = int(parts[1]) if len(parts) > 1 else 40
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
        elif cmd == "refuse":
            from base import FixedPasskeyDelegate
            FixedPasskeyDelegate.refuse = True
            if self.device.keystore is not None:
                await self.device.keystore.delete_all()
            await self._disconnect_all()
            log.info("Sensor esqueceu o celular e passa a recusar pareamento")
        elif cmd == "accept":
            from base import FixedPasskeyDelegate
            FixedPasskeyDelegate.refuse = False
            log.info("Sensor volta a aceitar pareamento")
        elif cmd == "restart":
            self.start = now_utc()
            open(f"smartguide-{self.serial}-inicio.txt", "w", encoding="utf-8").write(self.start.isoformat())
            self.records.clear()
            self.session_ended = False
            log.info("Sensor novo: contagem recomeça do minuto 0")
        else:
            log.info("Comando desconhecido: %s", cmd)

    async def _come_back(self, minutes: int) -> None:
        await asyncio.sleep(minutes * 60)
        self.away_until = None
        self.device.paused_advertising = False
        log.info("Sensor de volta ao alcance")
        await self.device.advertise_again()

    async def _disconnect_all(self) -> None:
        for conn in list(self.device.connections.values()):
            try:
                await conn.disconnect()
            except Exception as e:  # noqa: BLE001
                log.info("Falha ao desconectar: %s", e)


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="1R009999990")
    ap.add_argument("--pin", type=int, default=123456)
    ap.add_argument("--start-minute", type=int, default=4762, help="idade do sensor ao ligar (min)")
    ap.add_argument("--live-every", type=int, default=60, help="segundos entre leituras ao vivo")
    ap.add_argument("--address", default="F0:A1:B2:C3:D4:E5")
    ap.add_argument("--transport", default="android-netsim")
    ap.add_argument("--novo", action="store_true", help="sensor novo: ignora o início guardado")
    args = ap.parse_args()

    setup_logging("smartguide")
    sensor = SmartGuide(args.serial, args.start_minute)
    # Relógio do sensor não volta atrás: o início fica guardado entre execuções (--novo zera).
    import os
    inicio = f"smartguide-{args.serial}-inicio.txt"
    if os.path.exists(inicio) and not args.novo:
        sensor.start = dt.datetime.fromisoformat(open(inicio, encoding="utf-8").read().strip())
        sensor.records.clear()
        sensor._seed_history()
    else:
        open(inicio, "w", encoding="utf-8").write(sensor.start.isoformat())
    name = f"AC-{args.serial}"
    from base import advertising_data
    sensor._adv = advertising_data(name, 0x181F)

    async def on_ready(device) -> None:
        sensor.device = device
        asyncio.get_event_loop().create_task(sensor.live_loop(args.live_every))
        asyncio.get_event_loop().create_task(sensor.control_loop("control.txt"))
        log.info("Sensor no minuto %d, %d registros no histórico", sensor.minute_now(), len(sensor.records))

    await run_peripheral(name, args.address, sensor.services(), 0x181F, args.pin, on_ready, args.transport)


if __name__ == "__main__":
    asyncio.run(main())
