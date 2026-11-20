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
 *                         Em teste — implementado, ainda sem validação num sensor real.
 * - SIBIONICS_CHINES:     BLE direto, protocolo v1.1.2 com autenticação (hardware chinês)
 * - SIBIONICS_GS3:        BLE direto, protocolo GS3 (Kotlin puro, sem .so). Sem teste.
 * - LIBRE2_PLUS:          BLE direto FreeStyle Libre 2 Plus (ativação NFC + streaming cifrado)
 * - LIBRE3:               BLE direto FreeStyle Libre 3 / Lingo (NFC + handshake cripto whitebox,
 *                         fresh/saved auth, kAuth 149B). Porte fiel Juggluco (GPLv3). Sem teste.
 * - ACCU_CHEK_SMARTGUIDE: BLE direto, serviço CGM padrão da Bluetooth SIG (0x181F) — sem cripto
 *                         proprietária nem .so. Sem teste com sensor real até a validação (F5 do
 *                         plano 12_sprint_accuchek_smartguide.md).
 * - MEDLEVENSOHN_SMART2:  BLE direto, sensor Microtech MD (AiDEX X/LinX/Lumi rebrand) — cripto
 *                         própria (MD5 deriva IV+chave do serial, AES-128-CFB128, CRC8/16/32).
 *                         Sem teste com sensor real — implementado (MedLevensohnSource; handshake+glicemia
 *                         atual+lote de histórico). Liberado a todos em 15/11/2026 (decisão do Felipe)
 *                         depois da bateria do sensor virtual (plano 14_, V2: 9/12 ponta a ponta); o
 *                         caminho pareado (f003 ao vivo, fim do sensor) só foi coberto por teste.
 * - DEXCOM_G6:            BLE direto — handshake desafio-resposta AES-128 (chave derivada do
 *                         Transmitter ID digitado pelo usuário, sem troca de chave). Sem teste
 *                         — implementado (DexcomG6Source, porte do driver nativo do xDrip),
 *                         nunca testado num transmissor real.
 * - DEXCOM_G7:            BLE direto — protocolo bem mais complexo que o G6 (EC-JPAKE sobre curva
 *                         elíptica P-256 + validação de certificados). Sem teste — SÓ o
 *                         formato do registro de glicemia pós-decifra está portado (DexcomG7Parser);
 *                         o handshake de pareamento NÃO está implementado (é um projeto de RE
 *                         criptográfica à parte — ver DexcomProtocol.kt).
 *
 * Nenhuma fonte depende de liberação do GluSync (antes as "Em breve" exigiam a beta
 * `sensor_beta:<id>` aprovada). O quanto cada uma foi validada mora em [SensorVariant.maturity].
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
    @StringRes val labelRes: Int
) {
    GLUSYNC("GLUSYNC", "GluSync (servidor)", R.string.data_source_glusync),
    SIBIONICS_NACIONAL("SIBIONICS_NACIONAL", "Sibionics Brasil", R.string.data_source_sibionics_brasil),
    SIBIONICS_CHINES("SIBIONICS_CHINES", "Sibionics China", R.string.data_source_sibionics_china),
    SIBIONICS_GS3("SIBIONICS_GS3", "Sibionics GS3", R.string.data_source_sibionics_gs3),
    LIBRE2_PLUS("LIBRE2_PLUS", "FreeStyle Libre 2 Plus", R.string.data_source_libre2_plus),
    LIBRE3("LIBRE3", "FreeStyle Libre 3 / Lingo", R.string.data_source_libre3),
    ACCU_CHEK_SMARTGUIDE("ACCU_CHEK_SMARTGUIDE", "Accu-Chek SmartGuide", R.string.data_source_accu_chek_smartguide),
    MEDLEVENSOHN_SMART2("MEDLEVENSOHN_SMART2", "Smart 2.0 Medlevensohn", R.string.data_source_medlevensohn_smart2),
    DEXCOM_G6("DEXCOM_G6", "Dexcom G6", R.string.data_source_dexcom_g6),
    DEXCOM_G7("DEXCOM_G7", "Dexcom G7", R.string.data_source_dexcom_g7),
    // F6.4 - leitores acoplados ao Libre (MiaoMiao/Tomato, Bubble, Atom). Sem teste com hardware real.
    // Porte fiel xDrip (GPLv3). Apenas Libre 1 e Libre 2; nunca Libre 2 Plus/3. Fonte nao confiavel para alca.
    LIBRE_BRIDGE_MIAOMIAO("LIBRE_BRIDGE_MIAOMIAO", "MiaoMiao (leitor Libre)", R.string.data_source_libre_bridge_miaomiao),
    LIBRE_BRIDGE_BUBBLE("LIBRE_BRIDGE_BUBBLE", "Bubble (leitor Libre)", R.string.data_source_libre_bridge_bubble),
    LIBRE_BRIDGE_ATOM("LIBRE_BRIDGE_ATOM", "Atom (leitor Libre)", R.string.data_source_libre_bridge_atom);

    /**
     * Verdadeiro apenas para os sensores Sibionics.
     * Continua marcando o pacote data/sibionics (calibração/status/UI específica de Sibionics).
     * Para "é um sensor lido localmente por BLE?" use [isLocalSensor].
     */
    val isSibionics: Boolean get() = this == SIBIONICS_NACIONAL || this == SIBIONICS_CHINES || this == SIBIONICS_GS3

    /** Dexcom G6/G7: configurado por código digitado (sem caixa para ler). */
    val isDexcom: Boolean get() = this == DEXCOM_G6 || this == DEXCOM_G7

/** Leitor externo acoplado ao Libre (MiaoMiao/Bubble/Atom), F6.4. */
    val isLibreBridge: Boolean get() =
        this == LIBRE_BRIDGE_MIAOMIAO || this == LIBRE_BRIDGE_BUBBLE || this == LIBRE_BRIDGE_ATOM

    /** Libre 3 / Lingo (F6.1). */
    val isLibre3: Boolean get() = this == LIBRE3

    /**
     * Sensor com 1 h de aquecimento acompanhada pelo app (contagem na Home, sem alarme de "sem
     * sinal" nesse período): Sibionics e Accu-Chek SmartGuide (aquecimento de 60 min, Juggluco
     * `mkdatabaseAccu`). Os dois informam a idade em minutos, de onde sai a contagem.
     */
    val hasWarmupWindow: Boolean get() = isSibionics || this == ACCU_CHEK_SMARTGUIDE || this == LIBRE3

    /**
     * Verdadeiro para qualquer fonte de sensor lida localmente pelo aparelho (BLE direto):
     * Sibionics (×2), Libre 2 Plus e Libre 3. Falso só para [GLUSYNC] (dado vem da nuvem).
     * É este o gate do caminho "sensor local" no serviço (FGS CONNECTED_DEVICE, upload à nuvem, etc.).
     */
    val isLocalSensor: Boolean get() = this != GLUSYNC

    /**
     * Depende de biblioteca nativa proprietária que só existe em arm64 (Sibionics GS1/GS2 e Libre 2
     * Plus). O Sibionics GS3 é porte Kotlin puro (sem .so) e não entra aqui. O Libre 3 usa
     * whitebox crypto nativa (gluviewlibre3.so) que também é arm64-only. Em aparelho 32 bits
     * ([NativeSensorSupport.isAvailable] = false) ou em build sem as bibliotecas
     * ([NativeSensorSupport.libsBundled] = false) fica indisponível.
     */
    val requiresNativeLibs: Boolean get() =
        this == SIBIONICS_NACIONAL || this == SIBIONICS_CHINES || this == LIBRE2_PLUS || this == LIBRE3

    /** Pode ser usada como fonte neste aparelho (ver [requiresNativeLibs]). */
    val isUsableOnThisDevice: Boolean get() =
        !requiresNativeLibs || (NativeSensorSupport.isAvailable && NativeSensorSupport.libsBundled(this))

    companion object {
        fun fromId(id: String?): DataSourceType =
            entries.firstOrNull { it.id == id } ?: GLUSYNC
    }
}
