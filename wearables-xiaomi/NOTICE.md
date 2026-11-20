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