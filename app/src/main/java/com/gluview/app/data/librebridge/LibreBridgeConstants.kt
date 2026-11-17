package com.gluview.app.data.librebridge

/**
 * Constantes dos leitores acoplados ao Libre (MiaoMiao/Tomato, Bubble, Atom),
 * porte fiel das referências xDrip+ (GPLv3).
 *
 * - Tomato.java:159-164 / Bubble.java:157-166 / Atom.java:140-152: acumulam FRAM e entregam
 *   a NFCReaderX.HandleGoodReading quando completo.
 * - Constants.java: LIBRE_1_2_FRAM_SIZE = 344.
 * - HM10Attributes.java: HM-10 (ffe0/ffe1) e Nordic UART (NUS) para BLE.
 */
object LibreBridgeConstants {
    const val LIBRE_1_2_FRAM_SIZE = 344

    // Cabeçalho/rodapé observados nos protocolos dos leitores (xDrip Tomato/Bubble/Atom).
    const val TOMATO_HEADER_LENGTH = 18
    const val TOMATO_PATCH_INFO_LENGTH = 6
    const val BUBBLE_FOOTER = 8
    const val ATOM_FOOTER = 8

    // HM-10 (MiaoMiao/Tomato e variações)
    const val HM10_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val HM10_RX_TX = "0000ffe1-0000-1000-8000-00805f9b34fb"

    // Nordic UART Service (alguns firmwares Bubble/Atom/MiaoMiao)
    const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e" // write
    const val NUS_TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e" // notify

    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    // Fator de conversão raw -> mg/dL usado pelo ecossistema Libre (xDrip Constants.LIBRE_MULTIPLIER).
    const val LIBRE_MULTIPLIER = 117.64705
}
