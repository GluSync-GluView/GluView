"""Sensor virtual Sibionics GS3 (Fase F6.3) — Bumble + netsim do emulador.

Simula o lado do sensor Sibionics GS3 (V3 AES-OFB e V2 RC4), compatível com o
protocolo do Juggluco 11.5.7 (interpretgs3.cpp, v3protocol.hpp, v3session.hpp)
e com o GluView (SibionicsGS3Protocol).

UUIDs BLE Sibionics:
  Serviço: 0000ff30-0000-1000-8000-00805f9b34fb
  Notify:  0000ff31-0000-1000-8000-00805f9b34fb (sensor -> app)
  Write:   0000ff32-0000-1000-8000-00805f9b34fb (app -> sensor)

GPLv3 (como o GluView).
"""
from __future__ import annotations

import argparse
import asyncio
import struct
import time
from typing import Optional

from bumble.att import Attribute
from bumble.gatt import Characteristic, CharacteristicValue, Service
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from base import hexs, log, now_utc, run_peripheral, setup_logging

P = Characteristic.Properties

FF30_SERVICE_UUID = "0000ff30-0000-1000-8000-00805f9b34fb"
FF31_NOTIFY_UUID  = "0000ff31-0000-1000-8000-00805f9b34fb"
FF32_WRITE_UUID   = "0000ff32-0000-1000-8000-00805f9b34fb"

TRANSPORT_KEY = bytes([
    0x01, 0x38, 0x0b, 0x9a, 0x00, 0x5b, 0x02, 0x5d,
    0xcd, 0x9e, 0xc3, 0x99, 0x09, 0x37, 0xaa, 0xe8
])

ALGORITHM_KEY = bytes([
    0x11, 0xb8, 0xab, 0x00, 0x01, 0x4b, 0x2a, 0x5f,
    0xcb, 0x91, 0xf3, 0xaa, 0x99, 0x27, 0xbf, 0xe0
])


def make_iv(address: bytes) -> bytes:
    # address: 6 bytes display order (ex: F6 09 FF E2 20 B2)
    # reversed at bytes 0..5, padded to 16 with zeros
    return bytes(reversed(address)) + b"\x00" * 10


def checksum(data: bytes) -> bytes:
    s = sum(data) & 0xFF
    return data + bytes([(-s) & 0xFF])


def valid_frame(p: bytes) -> bool:
    if len(p) < 4 or len(p) > 250:
        return False
    if p[0] + 1 != len(p):
        return False
    return (sum(p) & 0xFF) == 0


def ofb(key: bytes, iv: bytes, data: bytes) -> bytes:
    cipher = Cipher(algorithms.AES(key), modes.ECB())
    feedback = iv
    out = bytearray(len(data))
    for offset in range(0, len(data), 16):
        enc = cipher.encryptor()
        feedback = enc.update(feedback) + enc.finalize()
        chunk_len = min(16, len(data) - offset)
        for i in range(chunk_len):
            out[offset + i] = data[offset + i] ^ feedback[i]
    return bytes(out)


class GS3Sensor:
    def __init__(self, mac_bytes: bytes, name: str = "LT-GS3-1234"):
        self.mac_bytes = mac_bytes
        self.name = name
        self.iv = make_iv(mac_bytes)
        self.authenticated = False
        self.index = 100
        self.start_time = int(time.time()) - (self.index * 60)
        self.notify_char: Optional[Characteristic] = None

    def encrypt(self, plain: bytes) -> bytes:
        return ofb(TRANSPORT_KEY, self.iv, plain)

    def decrypt(self, wire: bytes) -> bytes:
        return ofb(TRANSPORT_KEY, self.iv, wire)

    def handle_write(self, value: bytes) -> Optional[bytes]:
        log(f"RX write len={len(value)}: {hexs(value)}")
        plain = self.decrypt(value)
        if not valid_frame(plain):
            log(f"Plain inválido: {hexs(plain)}")
            return None
        log(f"RX plain ({len(plain)}B): {hexs(plain)}")
        cmd = plain[1]

        # Auth request 0xe2
        if cmd == 0xe2:
            self.authenticated = True
            log("Auth recebida. Respondendo sucesso.")
            reply = checksum(bytes([0x04, 0xe2, 0x01, 0x00]))
            return self.encrypt(reply)

        # Query 0xf0
        if cmd == 0xf0:
            sub = plain[2]
            log(f"Query 0xf0 sub={hex(sub)}")
            if sub == 0x0f:
                # 5 bytes
                return self.encrypt(checksum(bytes([0x04, 0xf0, 0x0f, 0x00])))
            elif sub == 0x0e:
                # 29 bytes
                payload = bytes([0x1c, 0xf0, 0x0e]) + b"\x00" * 25
                return self.encrypt(checksum(payload))
            elif sub == 0x03:
                # Device time: 20 bytes
                # len=19(0x13), cmd=0xf0, sub=0x03, startover(2B), act(4B), curr(4B), last(4B), last_idx(2B), sum
                now_sec = int(time.time())
                act_time = self.start_time
                dt_bytes = bytes([0x13, 0xf0, 0x03]) + struct.pack("<HIIIH", 0, act_time, now_sec, now_sec, self.index)
                return self.encrypt(checksum(dt_bytes))
            elif sub == 0x11:
                return self.encrypt(checksum(bytes([0x04, 0xf0, 0x11, 0x00])))
            elif sub == 0x01:
                return self.encrypt(checksum(bytes([0x07, 0xf0, 0x01, 0x00, 0x00, 0x00, 0x00])))

        # History request 0x39
        if cmd == 0x39:
            start_req = struct.unpack("<H", plain[2:4])[0]
            log(f"History request a partir de {start_req}")
            # Envia ACK do history request
            ack = checksum(bytes([0x04, 0x39, 0x01, 0x00]))
            return self.encrypt(ack)

        return None

    def build_live_record(self, glucose_mgdl: int = 120) -> bytes:
        # cmd=0x32, 1 record
        # len=23 (0x17), cmd=0x32, count=1, start(2B)=index, record(16B), tail(2B)=0, sum
        self.index += 1
        rec = bytearray(16)
        struct.pack_into("<HHH", rec, 0, 365, 0, 1500) # temp, dump, current
        struct.pack_into("<H", rec, 6, glucose_mgdl)   # display glucose
        # algorithm glucose wire
        cipher = Cipher(algorithms.AES(ALGORITHM_KEY), modes.ECB())
        enc = cipher.encryptor()
        mask = enc.update(b"\x00" * 16) + enc.finalize()
        mask16 = struct.unpack("<H", mask[:2])[0]
        algo_wire = glucose_mgdl ^ mask16
        struct.pack_into("<H", rec, 8, algo_wire)
        rec[10] = 0 # trend=0, present_cstate=0
        rec[11] = 0
        struct.pack_into("<HH", rec, 12, 3000, 1500) # ce_volt, re_volt

        plain = bytes([0x17, 0x32, 0x01]) + struct.pack("<H", self.index) + bytes(rec) + struct.pack("<H", 0)
        return self.encrypt(checksum(plain))


def main():
    parser = argparse.ArgumentParser(description="Simulador Bumble GS3")
    parser.add_argument("--mac", default="F6:09:FF:E2:20:B2", help="MAC BLE simulado")
    args = parser.parse_args()
    setup_logging("gs3.log")

    mac_bytes = bytes.fromhex(args.mac.replace(":", ""))
    sensor = GS3Sensor(mac_bytes)
    log(f"GS3 simulador configurado com MAC {args.mac}")


if __name__ == "__main__":
    main()
