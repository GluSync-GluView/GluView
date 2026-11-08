"""
Base dos sensores virtuais do GluView (fase V0 do plano `Projeto DM1/GluView/14_simulacao_sensores_virtuais.md`).

Um sensor virtual é um periférico Bluetooth LE feito com o Bumble (pilha Bluetooth do Google) e ligado
ao Bluetooth virtual do emulador do Android Studio (netsim). O GluView, rodando no emulador, enxerga
esse periférico como um sensor de verdade e usa o mesmo código que usaria com o sensor físico.

Só dados sintéticos. GPLv3 (como o GluView).
"""
from __future__ import annotations

import asyncio
import datetime as dt
import logging
import sys

from bumble import hci
from bumble.device import Device
from bumble.pairing import PairingConfig, PairingDelegate
from bumble.transport import open_transport

log = logging.getLogger("virtual-sensor")


def setup_logging(name: str) -> None:
    fmt = logging.Formatter("%(asctime)s %(levelname).1s %(message)s", "%H:%M:%S")
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(fmt)
    file_handler = logging.FileHandler(f"{name}.log", encoding="utf-8")
    file_handler.setFormatter(fmt)
    log.handlers[:] = [handler, file_handler]
    log.setLevel(logging.INFO)


def hexs(data: bytes) -> str:
    return data.hex(" ")


def now_utc() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


class FixedPasskeyDelegate(PairingDelegate):
    """Sensor com PIN impresso: ele "mostra" o PIN fixo e o celular tem de digitá-lo (passkey entry)."""

    def __init__(self, passkey: int | None) -> None:
        io = (PairingDelegate.IoCapability.DISPLAY_OUTPUT_ONLY if passkey is not None
              else PairingDelegate.IoCapability.NO_OUTPUT_NO_INPUT)
        super().__init__(io_capability=io)
        self.passkey = passkey

    # Cenário "pareado com outro celular": o sensor recusa qualquer pareamento novo.
    refuse = False

    async def accept(self) -> bool:
        if FixedPasskeyDelegate.refuse:
            log.info("Pareamento RECUSADO (sensor preso a outro celular)")
            return False
        return True

    async def generate_passkey(self) -> int:
        log.info("Pareamento pedido — PIN do sensor: %06d", self.passkey or 0)
        return self.passkey or 0

    async def display_number(self, number: int, digits: int) -> None:
        log.info("Sensor 'mostra' o número %0*d", digits, number)


def advertising_data(name: str, service16: int) -> bytes:
    """Flags (LE geral, sem BR/EDR) + lista de serviços 16 bits + nome completo."""
    name_bytes = name.encode("ascii")
    return (bytes([2, 0x01, 0x06])
            + bytes([3, 0x03, service16 & 0xFF, service16 >> 8])
            + bytes([len(name_bytes) + 1, 0x09]) + name_bytes)


async def run_peripheral(name: str, address: str, services: list, service16: int,
                         passkey: int | None, on_ready=None, transport: str = "android-netsim") -> None:
    """Liga o periférico no netsim, anuncia e fica rodando até Ctrl+C."""
    log.info("Abrindo transporte %s", transport)
    async with await open_transport(transport) as hci_transport:
        device = Device.with_hci(name, hci.Address(address), hci_transport.source, hci_transport.sink)
        # Pareamento guardado em arquivo, como no sensor real: reiniciar o simulador não "esquece" o celular.
        from bumble.keys import JsonKeyStore
        device.keystore = JsonKeyStore(namespace=address, filename=f"{name}-pareamentos.json")
        device.pairing_config_factory = lambda _conn: PairingConfig(
            sc=True, mitm=passkey is not None, bonding=True, delegate=FixedPasskeyDelegate(passkey),
            # Identidade = o mesmo endereço aleatório estático do anúncio. Sem isso o Bumble manda o
            # endereço público do netsim e o Android passa a reconectar num endereço onde ninguém anuncia.
            identity_address_type=PairingConfig.AddressType.RANDOM)
        device.add_services(services)
        adv = advertising_data(name, service16)
        device.paused_advertising = False  # cenário "longe do celular" desliga o reanúncio

        async def advertise() -> None:
            if device.paused_advertising or device.is_advertising:
                return
            try:
                await device.start_advertising(own_address_type=hci.OwnAddressType.RANDOM,
                                               auto_restart=False, advertising_data=adv)
                log.info("Anunciando de novo")
            except Exception as e:  # noqa: BLE001
                log.info("Falha ao anunciar: %s", e)

        def on_disconnection(reason) -> None:
            log.info("Desconectado (motivo %s)", reason)
            # Sensor real volta a anunciar sempre que não está conectado.
            asyncio.get_event_loop().call_later(0.5, lambda: asyncio.ensure_future(advertise()))

        def on_connection(connection) -> None:
            log.info("Conectado: %s", connection.peer_address)
            connection.on("disconnection", on_disconnection)
            connection.on("pairing", lambda keys: log.info("Pareado"))
            connection.on("pairing_failure", lambda reason: log.info("Pareamento falhou (%s)", reason))

        device.on("connection", on_connection)
        device.advertise_again = advertise
        await device.power_on()
        await device.start_advertising(
            own_address_type=hci.OwnAddressType.RANDOM,
            auto_restart=False,
            advertising_data=adv,
        )
        log.info("Anunciando como %s (%s)", name, address)
        if on_ready is not None:
            await on_ready(device)
        await hci_transport.source.wait_for_termination()
