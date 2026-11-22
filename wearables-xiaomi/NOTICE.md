# NOTICE - :wearables-xiaomi module

This module includes code derived from **Gadgetbridge** (https://codeberg.org/Freeyourgadget/Gadgetbridge),
licensed under the **GNU Affero General Public License version 3 or later**.

## Derived Files (headers preserved)

The following files in `gadgetbridge-derived/xiaomi/watches/` are reference implementations
from Gadgetbridge, retained with original copyright and license headers:

- `MiBand7ProCoordinator.java` — Copyright (C) 2023-2024 José Rebelo
- `MiBand8Coordinator.java` — Copyright (C) 2023-2024 José Rebelo
- `MiBand8ProCoordinator.java` — Copyright (C) 2023 Yoran Vulker
- `MiBand9Coordinator.java` — Copyright (C) 2024
- `MiBand9ProCoordinator.java` — Copyright (C) 2024

These files are **not compiled** into this module; they serve as reference for the
protobuf/auth protocol implementation in `modern/XiaomiModernCoordinator.kt` and
`modern/XiaomiModernTransport.kt`.

## Original License

Gadgetbridge is free software: you can redistribute it and/or modify it under the terms
of the GNU Affero General Public License as published by the Free Software Foundation,
either version 3 of the License, or (at your option) any later version.

Full AGPLv3 text: https://www.gnu.org/licenses/agpl-3.0.txt

## GluView Additions

Original code in this module (not derived from Gadgetbridge):
- `XiaomiModernSupport.kt` — Model definitions, data classes, integration interfaces
- `modern/XiaomiModernTransport.kt` — WearableTransport implementation for F7.1 core
- `modern/XiaomiModernCoordinator.kt` — Protobuf auth/session management
- `proto/xiaomi.proto` — Protobuf message definitions

These additions are licensed under the same AGPLv3 as the module.
## Código derivado compilado (plano 21, F7.4, 22/11/2026)

Arquivos em `src/main/java/com/gluview/wearables/xiaomi/protocol/`, adaptados do Gadgetbridge com o cabeçalho
AGPL original (Copyright (C) 2023-2024 José Rebelo, Yoran Vulker e colaboradores):

| Arquivo | Origem no Gadgetbridge |
|---|---|
| `XiaomiCrypto.kt` | `service/devices/xiaomi/XiaomiAuthService.java` (derivação "miwear-auth", AES-CCM de 32 bits; AES-CTR do V2) |
| `XiaomiMessages.kt` | `GBProtobufGenerator/src/main/proto/xiaomi.proto`, `xiaomi/wear_account.proto`, `xiaomi/wear_notification.proto`, `xiaomi/wear_system.proto` (teste de vibração), `XiaomiAuthService.java`, `services/XiaomiNotificationService.java` |
| `XiaomiV1Frames.kt` | `service/devices/xiaomi/XiaomiCharacteristicV1.java`, `XiaomiUuids.java` |
| `XiaomiBandSession.kt` | `XiaomiBleProtocolV1.java`, `XiaomiCharacteristicV1.java`, `XiaomiBleProtocolV2.java`, `XiaomiAuthService.java` |
| `XiaomiV2Frames.kt` | `service/devices/xiaomi/XiaomiSppPacketV2.java`, `XiaomiBleProtocolV2.java`, `XiaomiUuids.java` (Mi Band 9 Active, BLE V2) |

Os testes (`src/test/.../protocol/XiaomiProtocolTest.kt`) usam vetores gerados de forma independente em
Python; a pulseira virtual (`tools/virtual-sensors/xiaomi.py`, GPLv3) implementa o lado da pulseira.
