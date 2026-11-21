"""Sensor virtual FreeStyle Libre 3 (plano 21, F6.1) — Bumble + netsim do emulador.

Faz o lado do SENSOR do protocolo do Juggluco 11.5.x (Libre3GattCallback.java / bluetooth.cpp),
no caminho de AUTORIZAÇÃO SALVA:
  - serviços/características com os UUIDs reais (089810cc-… dados, 0898203a-… segurança);
  - desafio: app manda 17 → sensor manda r1+nonce (23 bytes) → app responde cifrado (40 bytes) +
    comando 8 → sensor confere r1 e PIN e manda r2+r1+kEnc+ivEnc cifrado (67 bytes);
  - dados com AES-128-CCM (nonce 13 = sequência + descritor + ivEnc, tag 4), como o bcrypt.cpp:
    status do sensor, glicemia do minuto (35 bytes em 2 notificações), histórico, dados clínicos e
    registro de eventos, respondendo aos comandos de backfill do app.

A autorização NOVA não é simulável (o certificado do sensor é assinado pela Abbott). A kAuth é
fabricada pelo build debug do GluView (Libre3TestAuthorization) e a cripto do desafio do lado do
sensor roda no próprio app debug (funções Emulator* do núcleo), chamada por `adb shell am broadcast`.

Uso (emulador aberto, build debug instalado):
  ./.venv/Scripts/python.exe -u libre3.py --inject --live-every 20
Comandos em control-libre3.txt: `val N` (próximas glicemias), `lo`, `hi`, `away N` (some N s),
`drop`, `badtag` (próxima glicemia com tag errada), `reject` (recusa a kAuth no próximo desafio).

Só dados sintéticos. GPLv3 (como o GluView).
"""
from __future__ import annotations

import argparse
import asyncio
import json
import math
import os
import re
import struct
import subprocess
import time

from bumble.att import Attribute
from bumble.gatt import Characteristic, CharacteristicValue, Service
from cryptography.hazmat.primitives.ciphers.aead import AESCCM

from base import hexs, log, run_peripheral, setup_logging

P = Characteristic.Properties
RW = Attribute.Permissions.READABLE | Attribute.Permissions.WRITEABLE

DATA_SERVICE = "089810CC-EF89-11E9-81B4-2A2AE2DBCCE4"
SECURITY_SERVICE = "0898203A-EF89-11E9-81B4-2A2AE2DBCCE4"
CHAR_PATCH_CONTROL = "08981338-EF89-11E9-81B4-2A2AE2DBCCE4"
CHAR_PATCH_STATUS = "08981482-EF89-11E9-81B4-2A2AE2DBCCE4"
CHAR_EVENT_LOG = "08981BEE-EF89-11E9-81B4-2A2AE2DBCCE4"
CHAR_GLUCOSE = "0898177A-EF89-11E9-81B4-2A2AE2DBCCE4"
CHAR_HISTORIC = "0898195A-EF89-11E9-81B4-2A2AE2DBCCE4"
CHAR_CLINICAL = "08981AB8-EF89-11E9-81B4-2A2AE2DBCCE4"
CHAR_FACTORY = "08981D24-EF89-11E9-81B4-2A2AE2DBCCE4"
SEC_COMMAND = "08982198-EF89-11E9-81B4-2A2AE2DBCCE4"
SEC_CHALLENGE = "089822CE-EF89-11E9-81B4-2A2AE2DBCCE4"
SEC_CERT = "089823FA-EF89-11E9-81B4-2A2AE2DBCCE4"

DESC = [bytes([0, 0, 0]), bytes([0, 0, 0x0F]), bytes([0, 0, 0xF0]), bytes([0, 0x0F, 0]),
        bytes([0, 0xF0, 0]), bytes([0x0F, 0, 0]), bytes([0xF0, 0, 0]), bytes([0x44, 0, 0])]
K_CONTROL, K_CONTROL_RESP, K_STATUS, K_GLUCOSE, K_HISTORY, K_CLINICAL, K_EVENTS = 0, 1, 2, 3, 4, 5, 6


class Oracle:
    """Cripto do lado do sensor no GluView debug (Libre3SelfTestReceiver), por adb."""

    def __init__(self, adb: str, serial: str, pkg: str) -> None:
        self.base = [adb, "-s", serial, "shell", "am", "broadcast", "-n",
                     f"{pkg}/com.gluview.app.debug.Libre3SelfTestReceiver"]

    def call(self, op: str, **extras: str) -> str:
        args = self.base + ["--es", "op", op]
        for k, v in extras.items():
            args += ["--es", k, v]
        out = subprocess.run(args, capture_output=True, text=True, timeout=60).stdout
        m = re.search(r'data="([^"]*)"', out)
        if not m or m.group(1) == "ERRO":
            raise RuntimeError(f"oráculo {op} falhou: {out.strip()}")
        return m.group(1)


def glucose_at(minute: int, base: float) -> int:
    return int(round(base + 45 * math.sin(minute / 47.0) + 12 * math.sin(minute / 9.0)))


class Libre3Sensor:
    def __init__(self, args, oracle: Oracle) -> None:
        self.args = args
        self.oracle = oracle
        self.device = None
        self.kauth: str = ""
        self.pin = bytes.fromhex(args.pin)
        self.start = args.start
        self.base = 120.0
        self.overrides: list[int] = []
        self.reject_next = False
        self.badtag_next = False
        self.reset_session()

    def reset_session(self) -> None:
        self.r1 = b""
        self.nonce1 = b""
        self.reply = bytearray(40)
        self.ccm: AESCCM | None = None
        self.iv = b""
        self.out_seq = 1
        self.streaming = False

    # ---------------------------------------------------------------- tempo e valores
    def life_now(self) -> int:
        return max(0, int((time.time() * 1000 - self.start) // 60000))

    def value(self, life: int) -> int:
        return max(40, min(400, glucose_at(life, self.base)))

    # ---------------------------------------------------------------- cifra dos dados
    def enc(self, kind: int, plain: bytes) -> bytes:
        seq = self.out_seq
        self.out_seq += 1
        nonce = struct.pack("<H", seq & 0xFFFF) + DESC[kind] + self.iv
        ct = self.ccm.encrypt(nonce, plain, None)
        return ct + struct.pack("<H", seq & 0xFFFF)

    def dec(self, kind: int, packet: bytes) -> bytes:
        nonce = packet[-2:] + DESC[kind] + self.iv
        return self.ccm.decrypt(nonce, packet[:-2], None)

    async def notify(self, char: Characteristic, value: bytes) -> None:
        await self.device.notify_subscribers(char, value)

    async def send_fragments(self, char: Characteristic, payload: bytes) -> None:
        """Dados de segurança: 1 byte de sequência + até 19 bytes (MTU 23)."""
        seq = 0
        for off in range(0, len(payload), 19):
            await self.notify(char, bytes([seq]) + payload[off:off + 19])
            seq += 1
            await asyncio.sleep(0.02)

    # ---------------------------------------------------------------- segurança
    def write_command(self, _conn, value: bytes) -> None:
        cmd = value[0] if value else -1
        log.info("SEG <- comando %s", cmd)
        asyncio.get_event_loop().create_task(self._command(cmd))

    async def _command(self, cmd: int) -> None:
        await asyncio.sleep(0.05)
        if cmd == 17:  # desafio (autorização salva)
            self.reset_session()
            self.r1 = os.urandom(16)
            self.nonce1 = os.urandom(7)
            await self.notify(self.c_command, bytes([8, 23]))
            await self.send_fragments(self.c_challenge, self.r1 + self.nonce1)
            log.info("Desafio enviado (r1 + nonce)")
        elif cmd == 8:  # resposta do app escrita
            await self._answer_challenge()
        elif cmd in (1, 2, 3, 9, 13, 14):
            log.info("Autorização NOVA pedida (comando %d): não simulável (certificado da Abbott)", cmd)
        else:
            log.info("Comando de segurança ignorado: %s", cmd)

    def write_challenge(self, _conn, value: bytes) -> None:
        off = value[0] | (value[1] << 8)
        data = bytes(value[2:20])
        n = min(len(data), 40 - off)
        if 0 <= off < 40:
            self.reply[off:off + n] = data[:n]
        log.info("SEG <- fragmento do desafio no deslocamento %d", off)

    async def _answer_challenge(self) -> None:
        if self.reject_next:
            self.reject_next = False
            log.info("Cenário reject: recusando a kAuth (desconecta sem responder)")
            await self.drop()
            return
        plain = bytes.fromhex(await asyncio.to_thread(
            self.oracle.call, "sensor_decrypt", kauth=self.kauth, nonce=self.nonce1.hex(), data=bytes(self.reply).hex()))
        r1, r2, pin = plain[0:16], plain[16:32], plain[32:36]
        if r1 != self.r1 or pin != self.pin:
            log.info("Resposta do app NÃO confere (r1 %s, PIN %s)", r1 == self.r1, pin == self.pin)
            await self.drop()
            return
        k_enc, iv_enc, nonce2 = os.urandom(16), os.urandom(8), os.urandom(7)
        crypt = bytes.fromhex(await asyncio.to_thread(
            self.oracle.call, "sensor_encrypt", kauth=self.kauth, nonce=nonce2.hex(), data=(r2 + r1 + k_enc + iv_enc).hex()))
        self.ccm = AESCCM(k_enc, tag_length=4)
        self.iv = iv_enc
        await self.notify(self.c_command, bytes([8, 67]))
        await self.send_fragments(self.c_challenge, crypt + nonce2)
        log.info("Desafio respondido: app autorizado, chaves de dados trocadas")

    # ---------------------------------------------------------------- dados
    def on_subscription(self, _conn, char, notify: bool, _indicate: bool) -> None:
        log.info("Assinatura %s = %s", char.uuid, notify)
        if char is self.c_status and notify and self.ccm is not None:
            self.streaming = True
            asyncio.get_event_loop().create_task(self.send_minute())

    def minute_plain(self, life: int) -> bytes:
        cur = self.overrides.pop(0) if self.overrides else self.value(life)
        prev = self.value(life - 1)
        rate = int(round((cur - prev) * 100))
        trend = 3 if abs(cur - prev) < 1 else (4 if cur > prev else 2)
        hist_life = ((life - 9) // 5) * 5
        hist = self.value(hist_life)
        capped = 0 if cur < 40 or cur > 500 else cur
        return struct.pack("<HHhHHHHBHHH", life, capped, rate, 0, cur, hist_life, hist, trend, cur, hist, 3400) + bytes(8)

    def status_plain(self, life: int) -> bytes:
        return struct.pack("<hhhbbhbb", life, 0, 0, 0, 4, life, 0, 0)

    async def send_minute(self, force: bool = False) -> None:
        if not self.streaming or self.ccm is None:
            return
        life = self.life_now()
        # O sensor real manda cada minuto uma vez; `resend` (cenário) repete de propósito.
        if life == getattr(self, "last_sent", -1) and not force:
            return
        self.last_sent = life
        await self.notify(self.c_status, self.enc(K_STATUS, self.status_plain(life)))
        await asyncio.sleep(0.05)
        packet = self.enc(K_GLUCOSE, self.minute_plain(life))
        if self.badtag_next:
            self.badtag_next = False
            packet = bytes([packet[0] ^ 0x55]) + packet[1:]
            log.info("Cenário badtag: glicemia com tag errada")
        await self.notify(self.c_glucose, packet[:20])
        await self.notify(self.c_glucose, packet[20:])
        log.info("Minuto %d enviado", life)

    def write_control(self, _conn, value: bytes) -> None:
        asyncio.get_event_loop().create_task(self._control(bytes(value)))

    async def _control(self, packet: bytes) -> None:
        try:
            cmd = self.dec(K_CONTROL, packet)
        except Exception:  # noqa: BLE001
            log.info("Comando de controle com tag errada: %s", hexs(packet))
            return
        log.info("CONTROLE <- %s", hexs(cmd))
        life = self.life_now()
        if cmd[0] == 1 and cmd[1] == 0:  # histórico de 5 em 5 min
            start = struct.unpack("<i", cmd[3:7])[0]
            last_hist = ((life - 9) // 5) * 5
            first = max(start, 5)
            sent = 0
            lc = first - first % 5
            while lc <= last_hist:
                vals = [self.value(lc + 5 * i) for i in range(6) if lc + 5 * i <= last_hist]
                await self.notify(self.c_historic, self.enc(K_HISTORY, struct.pack("<H", lc) + b"".join(struct.pack("<H", v) for v in vals)))
                sent += len(vals)
                lc += 5 * len(vals)
                await asyncio.sleep(0.01)
            log.info("Histórico enviado: %d valores a partir de %d", sent, first)
        elif cmd[0] == 1 and cmd[1] == 1:  # dados clínicos de 1 min
            start = struct.unpack("<i", cmd[3:7])[0]
            first = max(start, max(1, life - self.args.clinical_max))
            for lc in range(first, life):
                hist_life = int(round((lc - 19.0) / 5.0)) * 5
                await self.notify(self.c_clinical, self.enc(K_CLINICAL, struct.pack("<H", lc) + bytes(8) + struct.pack("<HH", self.value(lc), self.value(hist_life))))
                await asyncio.sleep(0.01)
            log.info("Dados clínicos enviados: %d a %d", first, life - 1)
        elif cmd[0] == 4:  # registro de eventos
            await self.notify(self.c_events, self.enc(K_EVENTS, struct.pack("<hhhB", life, 0, 0, 0)))
            log.info("Registro de eventos enviado")
        await self.notify(self.c_control, self.enc(K_CONTROL_RESP, bytes([1])))

    async def drop(self) -> None:
        for conn in list(self.device.connections.values()):
            try:
                await conn.disconnect()
            except Exception as e:  # noqa: BLE001
                log.info("Falha ao desconectar: %s", e)

    # ---------------------------------------------------------------- laços
    async def live_loop(self, every: int) -> None:
        while True:
            await asyncio.sleep(every)
            await self.send_minute()

    async def control_loop(self, path: str) -> None:
        open(path, "a").close()
        while True:
            await asyncio.sleep(1)
            lines = open(path, encoding="utf-8").read().splitlines()
            if not lines:
                continue
            open(path, "w").close()
            for line in lines:
                parts = line.split()
                if not parts:
                    continue
                c = parts[0]
                log.info("Cenário: %s", line)
                if c == "val":
                    self.overrides += [int(x) for x in parts[1:]]
                elif c == "lo":
                    self.overrides.append(30)
                elif c == "hi":
                    self.overrides.append(600)
                elif c == "badtag":
                    self.badtag_next = True
                elif c == "resend":
                    await self.send_minute(force=True)
                elif c == "reject":
                    self.reject_next = True
                elif c == "drop":
                    await self.drop()
                elif c == "away":
                    self.device.paused_advertising = True
                    await self.drop()
                    await asyncio.sleep(int(parts[1]) if len(parts) > 1 else 120)
                    self.device.paused_advertising = False
                    await self.device.advertise_again()

    def services(self) -> list[Service]:
        self.c_control = Characteristic(CHAR_PATCH_CONTROL, P.WRITE | P.NOTIFY, RW, CharacteristicValue(write=self.write_control))
        self.c_status = Characteristic(CHAR_PATCH_STATUS, P.NOTIFY, RW, b"")
        self.c_events = Characteristic(CHAR_EVENT_LOG, P.NOTIFY, RW, b"")
        self.c_glucose = Characteristic(CHAR_GLUCOSE, P.NOTIFY, RW, b"")
        self.c_historic = Characteristic(CHAR_HISTORIC, P.NOTIFY, RW, b"")
        self.c_clinical = Characteristic(CHAR_CLINICAL, P.NOTIFY, RW, b"")
        self.c_factory = Characteristic(CHAR_FACTORY, P.NOTIFY, RW, b"")
        self.c_command = Characteristic(SEC_COMMAND, P.WRITE | P.NOTIFY, RW, CharacteristicValue(write=self.write_command))
        self.c_challenge = Characteristic(SEC_CHALLENGE, P.WRITE | P.NOTIFY, RW, CharacteristicValue(write=self.write_challenge))
        self.c_cert = Characteristic(SEC_CERT, P.WRITE | P.NOTIFY, RW, CharacteristicValue(write=lambda _c, v: log.info("SEG <- certificado %s", hexs(bytes(v)))))
        return [
            Service(DATA_SERVICE, [self.c_control, self.c_status, self.c_events, self.c_glucose,
                                   self.c_historic, self.c_clinical, self.c_factory]),
            Service(SECURITY_SERVICE, [self.c_command, self.c_challenge, self.c_cert]),
        ]


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--address", default="F3:13:55:A1:B2:C3")
    ap.add_argument("--serial", default="3MH00SIMUL")
    ap.add_argument("--pin", default="9a948f6e")
    ap.add_argument("--hours", type=float, default=3.0, help="idade do sensor ao começar (h)")
    ap.add_argument("--live-every", type=int, default=60)
    ap.add_argument("--clinical-max", type=int, default=24 * 60, help="dados clínicos guardados no sensor (min)")
    ap.add_argument("--adb", default=os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"))
    ap.add_argument("--device", default="emulator-5554")
    ap.add_argument("--pkg", default="com.gluview.app")
    ap.add_argument("--inject", action="store_true", help="grava a sessão no app (op=session) e reabre o app")
    ap.add_argument("--transport", default="android-netsim")
    args = ap.parse_args()
    setup_logging("libre3")

    state_file = f"libre3-{args.serial}-estado.json"
    oracle = Oracle(args.adb, args.device, args.pkg)
    if os.path.exists(state_file):
        st = json.load(open(state_file, encoding="utf-8"))
    else:
        st = {"start": int(time.time() * 1000 - args.hours * 3600_000), "kauth": oracle.call("kauth")}
        json.dump(st, open(state_file, "w", encoding="utf-8"))
    args.start = st["start"]
    sensor = Libre3Sensor(args, oracle)
    sensor.kauth = st["kauth"]
    log.info("Libre 3 virtual %s em %s, lifeCount %d", args.serial, args.address, sensor.life_now())

    if args.inject:
        res = oracle.call("session", serial=args.serial, mac=args.address, pin=args.pin, start=str(args.start), kauth=sensor.kauth)
        log.info("Sessão gravada no app: %s — reabrindo o app", res)
        subprocess.run([args.adb, "-s", args.device, "shell", "am", "force-stop", args.pkg])
        subprocess.run([args.adb, "-s", args.device, "shell", "monkey", "-p", args.pkg, "-c",
                        "android.intent.category.LAUNCHER", "1"], capture_output=True)

    async def on_ready(device) -> None:
        sensor.device = device
        device.on("characteristic_subscription", sensor.on_subscription)
        device.on("connection", lambda conn: conn.on("disconnection", lambda *_: sensor.reset_session()))
        asyncio.get_event_loop().create_task(sensor.live_loop(args.live_every))
        asyncio.get_event_loop().create_task(sensor.control_loop("control-libre3.txt"))

    await run_peripheral(f"ABBOTT{args.serial}", args.address, sensor.services(), 0xFDE3, None, on_ready, args.transport)


if __name__ == "__main__":
    asyncio.run(main())
