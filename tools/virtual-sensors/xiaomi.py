"""Pulseira Xiaomi nova virtual (Mi Band 8 = BLE V1, Mi Band 9 Active = BLE V2) — plano 21, F7.4 — Bumble + netsim.

Lado da PULSEIRA, escrito aqui de forma independente do código Kotlin do app (que vem do Gadgetbridge):
  serviço FE95; o app escreve em 0052 (a pulseira confirma em 0052) e a pulseira manda em 0051 (o app
  confirma em 0051). Pacote: u16 0 = controle, senão número do pedaço.
    00 00 02 [1 cifrado|2 claro] [u16 índice se cifrado] dados → comando curto; confirmação 00 00 03 00
    00 00 00 [cifrado] [u16 n] → início em pedaços; 00 00 01 01 pronto; pedaços; 00 00 01 00 recebido
  Aperto de mão (Command protobuf tipo 1):
    app → 26 {appVerify{nonce do celular}}
    pulseira → 26 {deviceVerify{nonce dela, HMAC(chaveDecifraDoApp, nonceDela‖nonceCelular)}}
    app → 27 {appConfirm{HMAC(chaveCifraDoApp, nonceCelular‖nonceDela), AES-CCM(CompanionDevice)}}
    pulseira → 27 (aceito)
  Chaves: HMAC(nonceCelular‖nonceDela, chave) → HMAC encadeado com "miwear-auth" → 64 bytes
  (0..15 decifra do app, 16..31 cifra do app, 32..35 nonce de decifra, 36..39 nonce de cifra).
  Depois: comandos cifrados AES-CCM (tag 4), nonce = nonceCifra‖0‖índice. Notificação = tipo 7 subtipo 0.

BLE V2 (`--proto v2`, Mi Band 9 Active): o app escreve em 005F e a pulseira notifica em 005E. Pacote =
  A5 A5, tipo (0 nack, 1 ack, 2 sessão, 3 dados), sequência, u16 tamanho, u16 CRC-16/ARC do conteúdo, conteúdo.
  Sessão: o app pede (opcode 1), a pulseira responde (opcode 2). Dados = canal (1 protobuf) + opcode
  (1 claro, 2 cifrado AES-CTR com IV = chave de cifra do app) + Command protobuf; quem recebe dados confirma.
Vibração personalizada (os dois): Command tipo 2 subtipo 59, System(4).vibrationTestCustom(41).vibration*.

Chave de TESTE (`--key`). Cenários (control-xiaomi.txt): `wrongkey` (a pulseira passa a usar outra chave),
`refuse` (recusa a confirmação), `drop` (cai depois da autenticação), `nack` (V2: recusa o próximo pacote de dados). Só dados sintéticos. GPLv3.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import hmac
import os
import struct

from bumble.att import Attribute
from bumble.gatt import Characteristic, CharacteristicValue, Service
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESCCM

from base import hexs, log, run_peripheral, setup_logging

P = Characteristic.Properties
RW = Attribute.Permissions.READABLE | Attribute.Permissions.WRITEABLE
SVC = "0000FE95-0000-1000-8000-00805F9B34FB"
CMD_READ = "00000051-0000-1000-8000-00805F9B34FB"    # pulseira → app
CMD_WRITE = "00000052-0000-1000-8000-00805F9B34FB"   # app → pulseira
ACT = "00000053-0000-1000-8000-00805F9B34FB"
UPL = "00000055-0000-1000-8000-00805F9B34FB"
V2_RX = "0000005E-0000-1000-8000-00805F9B34FB"      # pulseira → app (notifica)
V2_TX = "0000005F-0000-1000-8000-00805F9B34FB"      # app → pulseira (escreve)
PRE = bytes([0xA5, 0xA5])


def crc16_arc(data: bytes) -> int:
    """CRC-16/ARC na forma refletida clássica (polinômio 0xA001)."""
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def v2_frame(kind: int, seq: int, payload: bytes) -> bytes:
    return PRE + bytes([kind & 0xF, seq & 0xFF]) + struct.pack("<HH", len(payload), crc16_arc(payload)) + payload


def aes_ctr(key: bytes, data: bytes) -> bytes:
    c = Cipher(algorithms.AES(key), modes.CTR(key)).decryptor()
    return c.update(data) + c.finalize()


# ---- protobuf mínimo
def pb_parse(data: bytes) -> dict[int, list]:
    out: dict[int, list] = {}
    i = 0

    def varint():
        nonlocal i
        r = s = 0
        while True:
            b = data[i]
            i += 1
            r |= (b & 0x7F) << s
            if b < 0x80:
                return r
            s += 7

    while i < len(data):
        key = varint()
        f, w = key >> 3, key & 7
        if w == 0:
            v = varint()
        elif w == 2:
            n = varint()
            v = data[i:i + n]
            i += n
        elif w == 5:
            v = struct.unpack_from("<f", data, i)[0]
            i += 4
        elif w == 1:
            v = data[i:i + 8]
            i += 8
        else:
            raise ValueError("tipo de campo")
        out.setdefault(f, []).append(v)
    return out


def _varint(v: int) -> bytes:
    b = bytearray()
    while v > 0x7F:
        b.append((v & 0x7F) | 0x80)
        v >>= 7
    b.append(v)
    return bytes(b)


def pb_uint(f: int, v: int) -> bytes:
    return _varint(f << 3) + _varint(v)


def pb_bytes(f: int, v: bytes) -> bytes:
    return _varint((f << 3) | 2) + _varint(len(v)) + v


def derive(secret: bytes, phone: bytes, watch: bytes) -> bytes:
    k1 = hmac.new(phone + watch, secret, hashlib.sha256).digest()
    out, tmp, b = b"", b"", 1
    while len(out) < 64:
        tmp = hmac.new(k1, tmp + b"miwear-auth" + bytes([b]), hashlib.sha256).digest()
        out += tmp
        b += 1
    return out[:64]


class Band:
    def __init__(self, args) -> None:
        self.secret = bytes.fromhex(args.key)
        self.device = None
        self.phone = self.watch = b""
        self.keys = None
        self.authed = False
        self.chunks_expected = 0
        self.chunks: dict[int, bytes] = {}
        self.chunk_enc = False
        self.app_acks: asyncio.Queue = asyncio.Queue()
        self.refuse = False
        self.drop = False
        self.notifications = 0
        self.vibrations = 0
        self.v2 = args.proto == "v2"
        self.rx = b""
        self.band_seq = 0
        self.nack = False

    def services(self) -> list[Service]:
        if self.v2:
            self.v2rx = Characteristic(V2_RX, P.NOTIFY | P.READ, RW, b"")
            self.v2tx = Characteristic(V2_TX, P.WRITE | P.WRITE_WITHOUT_RESPONSE, RW, CharacteristicValue(write=self.on_v2_write))
            return [Service(SVC, [self.v2rx, self.v2tx])]
        self.read = Characteristic(CMD_READ, P.NOTIFY | P.WRITE | P.WRITE_WITHOUT_RESPONSE, RW,
                                   CharacteristicValue(write=self.on_read_write))
        self.write = Characteristic(CMD_WRITE, P.NOTIFY | P.WRITE | P.WRITE_WITHOUT_RESPONSE, RW,
                                    CharacteristicValue(write=self.on_write))
        self.act = Characteristic(ACT, P.NOTIFY | P.WRITE_WITHOUT_RESPONSE, RW, b"")
        self.upl = Characteristic(UPL, P.NOTIFY | P.WRITE_WITHOUT_RESPONSE, RW, b"")
        return [Service(SVC, [self.read, self.write, self.act, self.upl])]

    async def notify(self, ch, value: bytes) -> None:
        await self.device.notify_subscribers(ch, value)
        await asyncio.sleep(0.01)

    # ---- V2: tudo chega em 005F, em pedaços do tamanho do MTU
    def on_v2_write(self, _conn, value: bytes) -> None:
        self.rx += bytes(value)
        while len(self.rx) >= 8:
            if self.rx[:2] != PRE:
                i = self.rx.find(PRE[:1], 1)
                self.rx = self.rx[i:] if i > 0 else b""
                continue
            kind, seq = self.rx[2] & 0xF, self.rx[3]
            n, crc = struct.unpack_from("<HH", self.rx, 4)
            if len(self.rx) < 8 + n:
                return
            payload, self.rx = self.rx[8:8 + n], self.rx[8 + n:]
            if crc16_arc(payload) != crc:
                log.info("APP(V2) pacote com CRC ERRADO (seq %d): descartado", seq)
                continue
            asyncio.get_event_loop().create_task(self.handle_v2(kind, seq, payload))

    async def handle_v2(self, kind: int, seq: int, payload: bytes) -> None:
        if kind == 2:
            log.info("APP(V2) pede sessão: %s", hexs(payload))
            self.band_seq = 0   # sessão nova: a numeração da pulseira recomeça
            if payload[:1] == bytes([1]):
                await self.notify(self.v2rx, v2_frame(2, 0, bytes([2, 1, 3, 0, 1, 0, 0, 2, 2, 0, 0, 0xFC])))
        elif kind == 1:
            log.info("APP(V2) confirma o pacote %d", seq)
            self.app_acks.put_nowait(seq)
        elif kind == 0:
            log.info("APP(V2) recusou o pacote %d", seq)
        elif kind == 3:
            if self.nack:
                self.nack = False
                log.info("Recusando (NACK) o pacote %d do app", seq)
                await self.notify(self.v2rx, v2_frame(0, seq, b""))
                return
            await self.notify(self.v2rx, v2_frame(1, seq, b""))
            channel, opcode, body = payload[0] & 0xF, payload[1], payload[2:]
            log.info("APP(V2) -> dados seq %d canal %d %s", seq, channel, "cifrado" if opcode == 2 else "claro")
            if opcode == 2:
                if not self.keys:
                    log.info("Comando cifrado ANTES da autenticação: recusado")
                    return
                body = aes_ctr(self.keys[16:32], body)
            try:
                await self.process(body)
            except Exception as e:  # noqa: BLE001
                log.info("Comando ilegível (%s): chave/cifra errada?", e)

    # ---- confirmações do app chegam em 0051
    def on_read_write(self, _conn, value: bytes) -> None:
        v = bytes(value)
        log.info("APP(0051) -> %s", hexs(v))
        self.app_acks.put_nowait(v)

    # ---- comandos do app chegam em 0052
    def on_write(self, _conn, value: bytes) -> None:
        v = bytes(value)
        log.info("APP(0052) -> %s", hexs(v[:24]) + (" …" if len(v) > 24 else ""))
        asyncio.get_event_loop().create_task(self.handle_frame(v))

    async def handle_frame(self, v: bytes) -> None:
        chunk = struct.unpack_from("<H", v, 0)[0]
        if chunk != 0:
            self.chunks[chunk] = v[2:]
            if len(self.chunks) == self.chunks_expected:
                data = b"".join(self.chunks[i] for i in range(1, self.chunks_expected + 1))
                await self.notify(self.write, bytes([0, 0, 1, 0]))
                if self.chunk_enc:
                    idx = struct.unpack_from("<H", data, 0)[0]
                    await self.process(self.decrypt_app(data[2:], idx))
                else:
                    await self.process(data)
            return
        kind = v[2]
        if kind == 0:                         # início em pedaços
            self.chunk_enc = v[3] == 1
            self.chunks_expected = struct.unpack_from("<H", v, 4)[0]
            self.chunks = {}
            await self.notify(self.write, bytes([0, 0, 1, 1]))
        elif kind == 2:                       # comando curto
            enc = v[3] == 1
            if enc:
                idx = struct.unpack_from("<H", v, 4)[0]
                payload = self.decrypt_app(v[6:], idx)
            else:
                payload = v[4:]
            await self.notify(self.write, bytes([0, 0, 3, 0 if payload is not None else 1]))
            if payload is not None:
                await self.process(payload)

    def decrypt_app(self, ct: bytes, idx: int) -> bytes | None:
        if not self.keys:
            log.info("Comando cifrado ANTES da autenticação: recusado")
            return None
        enc_key, enc_nonce = self.keys[16:32], self.keys[36:40]
        try:
            return AESCCM(enc_key, tag_length=4).decrypt(enc_nonce + bytes(4) + struct.pack("<I", idx), ct, None)
        except Exception:  # noqa: BLE001
            log.info("Comando cifrado com etiqueta ERRADA (índice %d)", idx)
            return None

    async def send_command(self, payload: bytes) -> None:
        """Pulseira → app em claro (V1: 0051, espera 00 00 03 00; V2: 005E, espera o ACK do pacote)."""
        while not self.app_acks.empty():
            self.app_acks.get_nowait()
        if self.v2:
            seq, self.band_seq = self.band_seq, (self.band_seq + 1) & 0xFF
            frame = v2_frame(3, seq, bytes([1, 1]) + payload)
            mtu = 20
            for conn in self.device.connections.values():
                mtu = max(20, conn.att_mtu - 3)
            for i in range(0, len(frame), mtu):
                await self.notify(self.v2rx, frame[i:i + mtu])
        else:
            await self.notify(self.read, bytes([0, 0, 2, 2]) + payload)
        try:
            ack = await asyncio.wait_for(self.app_acks.get(), 5)
            log.info("Confirmação do app: %s", f"pacote {ack}" if isinstance(ack, int) else hexs(ack))
        except asyncio.TimeoutError:
            log.info("App NÃO confirmou o comando da pulseira")

    async def process(self, payload: bytes) -> None:
        c = pb_parse(payload)
        ctype, sub = c.get(1, [None])[0], c.get(2, [None])[0]
        if ctype == 1 and sub == 26:
            acc = pb_parse(c[3][0])
            self.phone = pb_parse(acc[30][0])[1][0]
            self.watch = os.urandom(16)
            self.keys = derive(self.secret, self.phone, self.watch)
            mac = hmac.new(self.keys[0:16], self.watch + self.phone, hashlib.sha256).digest()
            dv = pb_bytes(1, self.watch) + pb_bytes(2, mac)
            log.info("Passo 1 recebido (nonce do celular); mandando o da pulseira")
            await self.send_command(pb_uint(1, 1) + pb_uint(2, 26) + pb_bytes(3, pb_bytes(31, dv)))
        elif ctype == 1 and sub == 27:
            conf = pb_parse(pb_parse(c[3][0])[32][0])
            want = hmac.new(self.keys[16:32], self.phone + self.watch, hashlib.sha256).digest()
            ok_nonces = conf[1][0] == want
            info = None
            try:
                info = AESCCM(self.keys[16:32], tag_length=4).decrypt(self.keys[36:40] + bytes(8), conf[2][0], None)
            except Exception:  # noqa: BLE001
                pass
            if info is not None:
                d = pb_parse(info)
                log.info("Dados do celular: api %.0f, modelo '%s', região %s", d[2][0], d[3][0].decode(), d[5][0].decode())
            ok = ok_nonces and info is not None and not self.refuse
            self.authed = ok
            log.info("Autenticação %s", "ACEITA" if ok else "RECUSADA")
            await self.send_command(pb_uint(1, 1) + pb_uint(2, 27 if ok else 26) + pb_uint(100, 0 if ok else 1))
            if ok and self.drop:
                self.drop = False
                for conn in list(self.device.connections.values()):
                    await conn.disconnect()
        elif ctype == 2 and sub == 59:
            test = pb_parse(pb_parse(c[4][0])[41][0])
            parts = []
            for raw in test.get(1, []):
                v = pb_parse(raw)
                on, ms = v.get(1, [0])[0], v.get(2, [0])[0]
                parts.append(f"{'vibra' if on else 'pausa'} {ms} ms" + (f" ({v[3][0]}%)" if on and 3 in v else ""))
            self.vibrations += 1
            log.info("VIBRAÇÃO personalizada: %s", ", ".join(parts))
        elif ctype == 7 and sub == 0:
            n3 = pb_parse(pb_parse(pb_parse(c[9][0])[3][0])[1][0])
            self.notifications += 1
            log.info("NOTIFICAÇÃO na pulseira (vibra): [%s] %s — %s (%s)", n3[2][0].decode(), n3[3][0].decode(),
                     n3[5][0].decode(), n3[6][0].decode())
        else:
            log.info("Comando tipo %s/%s ignorado", ctype, sub)

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
                    self.secret = os.urandom(16)
                elif c == "refuse":
                    self.refuse = True
                elif c == "drop":
                    self.drop = True
                elif c == "nack":
                    self.nack = True


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--proto", choices=["v1", "v2"], default="v1", help="v1 = Mi Band 8; v2 = Mi Band 9 Active")
    ap.add_argument("--address", default=None)
    ap.add_argument("--name", default=None)  # nome curto: o anúncio tem de caber em 31 bytes
    ap.add_argument("--key", default="00112233445566778899aabbccddeeff", help="chave de TESTE (16 bytes em hex)")
    ap.add_argument("--mtu", type=int, default=247)
    ap.add_argument("--transport", default="android-netsim")
    args = ap.parse_args()
    if args.address is None:
        args.address = "F3:22:33:44:77:09" if args.proto == "v2" else "F3:22:33:44:77:08"
    if args.name is None:
        args.name = "Band 9 Active S" if args.proto == "v2" else "Smart Band 8 S"
    setup_logging("xiaomi")
    band = Band(args)

    async def on_ready(device) -> None:
        band.device = device
        device.gatt_server.max_mtu = args.mtu
        asyncio.get_event_loop().create_task(band.control_loop("control-xiaomi.txt"))

    await run_peripheral(args.name, args.address, band.services(), 0xFE95, None, on_ready, args.transport)


if __name__ == "__main__":
    asyncio.run(main())
