package com.gluview.app.data.source

import androidx.annotation.StringRes
import com.gluview.app.R

/**
 * Fonte de dados de glicemia selecionável (modelo xDrip).
 * Todas as fontes produzem GlucoseReading e caem no mesmo pipeline
 * (Room → notificação/widgets/alertas → GluSync).
 *
 * - GLUSYNC:              polling + SignalR no GluSyncService (padrão atual)
 * - SIBIONICS_NACIONAL:   BLE direto, protocolo v1.1.0 (sensor distribuído no Brasil).
 *                         [comingSoon] — implementado, mas nunca testado num sensor real.
 * - SIBIONICS_CHINES:     BLE direto, protocolo v1.1.2 com autenticação (hardware chinês)
 * - LIBRE2_PLUS:          BLE direto FreeStyle Libre 2 Plus (ativação NFC + streaming cifrado)
 * - ACCU_CHEK_SMARTGUIDE: BLE direto, serviço CGM padrão da Bluetooth SIG (0x181F) — sem cripto
 *                         proprietária nem .so. [comingSoon] até a validação no sensor real (F5 do
 *                         plano 12_sprint_accuchek_smartguide.md).
 * - MEDLEVENSOHN_SMART2:  BLE direto, sensor Microtech MD (AiDEX X/LinX/Lumi rebrand) — cripto
 *                         própria (MD5 deriva IV+chave do serial, AES-128-CFB128, CRC8/16/32).
 *                         [comingSoon] — implementado (MedLevensohnSource; handshake+glicemia
 *                         atual+lote de histórico), cripto validada com vetor real do Juggluco,
 *                         mas a máquina de estados do GATT nunca rodou num sensor real.
 * - DEXCOM_G6:            BLE direto — handshake desafio-resposta AES-128 (chave derivada do
 *                         Transmitter ID digitado pelo usuário, sem troca de chave). [comingSoon]
 *                         — implementado (DexcomG6Source, porte do driver nativo do xDrip),
 *                         nunca testado num transmissor real.
 * - DEXCOM_G7:            BLE direto — protocolo bem mais complexo que o G6 (EC-JPAKE sobre curva
 *                         elíptica P-256 + validação de certificados). [comingSoon] — SÓ o
 *                         formato do registro de glicemia pós-decifra está portado (DexcomG7Parser);
 *                         o handshake de pareamento NÃO está implementado (é um projeto de RE
 *                         criptográfica à parte — ver DexcomProtocol.kt).
 *
 * [label] é o nome técnico fixo (logs/appLogger, sempre em português); [labelRes] é a string
 * localizada usada na UI (assistente de sensor e card "Sensor atual") — necessário porque nomes como
 * "Sibionics China/Brasil" traduzem diferente ("China"/"Brazil") conforme o idioma do aparelho.
 *
 * Frente A (xDrip bridge) será adicionada como XDRIP_BRIDGE futuramente.
 */
enum class DataSourceType(
    val id: String,
    val label: String,
    @StringRes val labelRes: Int,
    val comingSoon: Boolean = false
) {
    GLUSYNC("GLUSYNC", "GluSync (servidor)", R.string.data_source_glusync),
    SIBIONICS_NACIONAL("SIBIONICS_NACIONAL", "Sibionics Brasil", R.string.data_source_sibionics_brasil, comingSoon = true),
    SIBIONICS_CHINES("SIBIONICS_CHINES", "Sibionics China", R.string.data_source_sibionics_china),
    LIBRE2_PLUS("LIBRE2_PLUS", "FreeStyle Libre 2 Plus", R.string.data_source_libre2_plus),
    ACCU_CHEK_SMARTGUIDE("ACCU_CHEK_SMARTGUIDE", "Accu-Chek SmartGuide", R.string.data_source_accu_chek_smartguide, comingSoon = true),
    MEDLEVENSOHN_SMART2("MEDLEVENSOHN_SMART2", "Smart 2.0 Medlevensohn", R.string.data_source_medlevensohn_smart2, comingSoon = true),
    DEXCOM_G6("DEXCOM_G6", "Dexcom G6", R.string.data_source_dexcom_g6, comingSoon = true),
    DEXCOM_G7("DEXCOM_G7", "Dexcom G7", R.string.data_source_dexcom_g7, comingSoon = true);

    /**
     * Verdadeiro apenas para os sensores Sibionics.
     * Continua marcando o pacote data/sibionics (calibração/status/UI específica de Sibionics).
     * Para "é um sensor lido localmente por BLE?" use [isLocalSensor].
     */
    val isSibionics: Boolean get() = this == SIBIONICS_NACIONAL || this == SIBIONICS_CHINES

    /**
     * Sensor com 1 h de aquecimento acompanhada pelo app (contagem na Home, sem alarme de "sem
     * sinal" nesse período): Sibionics e Accu-Chek SmartGuide (aquecimento de 60 min, Juggluco
     * `mkdatabaseAccu`). Os dois informam a idade em minutos, de onde sai a contagem.
     */
    val hasWarmupWindow: Boolean get() = isSibionics || this == ACCU_CHEK_SMARTGUIDE

    /**
     * Verdadeiro para qualquer fonte de sensor lida localmente pelo aparelho (BLE direto):
     * Sibionics (×2) e Libre 2 Plus. Falso só para [GLUSYNC] (dado vem da nuvem).
     * É este o gate do caminho "sensor local" no serviço (FGS CONNECTED_DEVICE, upload à nuvem, etc.).
     */
    val isLocalSensor: Boolean get() = this != GLUSYNC

    /**
     * Depende de biblioteca nativa proprietária que só existe em arm64 (Sibionics ×2 e Libre 2
     * Plus). Em aparelho 32 bits ([NativeSensorSupport.isAvailable] = false) ou em build sem as
     * bibliotecas ([NativeSensorSupport.libsBundled] = false) fica indisponível.
     */
    val requiresNativeLibs: Boolean get() = isSibionics || this == LIBRE2_PLUS

    /** Pode ser usada como fonte neste aparelho (ver [requiresNativeLibs]). */
    val isUsableOnThisDevice: Boolean get() =
        !requiresNativeLibs || (NativeSensorSupport.isAvailable && NativeSensorSupport.libsBundled(this))

    companion object {
        fun fromId(id: String?): DataSourceType =
            entries.firstOrNull { it.id == id } ?: GLUSYNC
    }
}
