package com.gluview.app.data.sensor

/**
 * Identidade de um sensor derivada do código impresso na embalagem.
 *
 * É o que permite ao app achar o sensor sozinho: o usuário escaneia a caixa e o app passa a saber
 * QUAL rádio procurar, em vez de pedir que ele escolha um endereço numa lista.
 *
 * @param connectionCode código de conexão de 8 caracteres (连接码). No Sibionics sai das posições
 *        6..13 do serial GS1 AI(21) — ver [com.gluview.app.data.sibionics.SibionicsQrParser].
 * @param serial serial completo da embalagem, quando disponível (diagnóstico/histórico).
 */
data class SensorCode(
    val connectionCode: String,
    val serial: String = ""
) {
    val isValid: Boolean get() = connectionCode.length >= MIN_CODE_LENGTH

    companion object {
        /** Mínimo para conseguir casar com o nome anunciado (ver [SibionicsIdentityMatcher]). */
        const val MIN_CODE_LENGTH = 4
    }
}

/**
 * Decide se um dispositivo anunciando no ar é o sensor daquela caixa.
 *
 * Uma implementação por família de sensor — mesmo desenho do Juggluco, onde o scan BLE é único e
 * compartilhado e cada família só contribui com sua regra de reconhecimento
 * (`SuperGattCallback.matchDeviceName`). Mantém o transporte fora da regra de identidade.
 */
interface SensorIdentityMatcher {
    /**
     * @param advertisedName nome anunciado no advertising/scan record (pode ser null).
     * @param code identidade derivada da embalagem escaneada.
     */
    fun matches(advertisedName: String?, code: SensorCode): Boolean
}

/**
 * Regra de identidade do Sibionics: **os 4 primeiros caracteres do código de conexão são os 4
 * últimos do nome anunciado pelo sensor**.
 *
 * Confirmada em duas fontes independentes:
 *
 *  - **Juggluco** (open source) — `SiGattCallback.matchDeviceName()`:
 *    `bluetoothNum.regionMatches(0, deviceName, len-4, 4)`, com `bluetoothNum` = 8 chars saídos do
 *    código da caixa (`SensorGlucoseData.hpp`, posições 28..35 do QR).
 *  - **App oficial SugarJoy+** (engenharia reversa) — `LocalBleService.A()` manda escanear com
 *    `blueToothNum.substring(0, 4)` e `r42.a()` compara contra os 4 últimos do nome anunciado.
 *
 * O prefixo da família ("LT", da linha LT25xx) é exigido antes da comparação para descartar ruído
 * do ambiente — dispositivos aleatórios cujo nome por acaso termine nos mesmos 4 caracteres não
 * chegam a ser considerados.
 */
class SibionicsIdentityMatcher : SensorIdentityMatcher {

    override fun matches(advertisedName: String?, code: SensorCode): Boolean {
        val name = advertisedName?.trim().orEmpty()
        if (name.isEmpty() || !code.isValid) return false
        if (!name.startsWith(FAMILY_PREFIX, ignoreCase = true)) return false
        if (name.length < SensorCode.MIN_CODE_LENGTH) return false

        val tail   = name.takeLast(SensorCode.MIN_CODE_LENGTH)
        val target = code.connectionCode.take(SensorCode.MIN_CODE_LENGTH)
        return tail.equals(target, ignoreCase = true)
    }

    companion object {
        /** Família LT25xx — todo sensor Sibionics Chinês anuncia com este prefixo. */
        const val FAMILY_PREFIX = "LT"
    }
}
