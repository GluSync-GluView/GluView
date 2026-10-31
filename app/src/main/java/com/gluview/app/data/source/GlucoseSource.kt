package com.gluview.app.data.source

/**
 * Fonte de glicemia lida localmente pelo aparelho (BLE direto do sensor).
 *
 * É o seam compartilhado entre as famílias de sensor. O transporte BLE NÃO é compartilhado —
 * cada família tem seus quirks (o Libre exige NFC + bonding; o Sibionics não) e é dona do seu
 * próprio BLE manager + protocolo + cripto. O que todas têm em comum é este ciclo de vida e o
 * destino do dado:
 *   sensor → GlucoseRepository.storeExternalReading(...) → Room → notificação/widgets/alertas/GluSync
 *   sensor → SibionicsStatusHolder (status genérico do sensor, lido por Home/Diagnóstico).
 *
 * Implementações:
 *   - [com.gluview.app.data.sibionics.SibionicsBleSource] (Sibionics v1.1.0 / v1.1.2)
 *   - [com.gluview.app.data.libre2.Libre2Source]          (FreeStyle Libre 2 Plus)
 *
 * O [com.gluview.app.service.GluSyncService] escolhe a implementação via
 * [SensorSourceRegistry] a partir do [DataSourceType] configurado.
 */
interface GlucoseSource {
    /** Inicia a leitura do sensor. Idempotente: uma segunda chamada com a fonte já ativa é no-op. */
    fun start(type: DataSourceType)

    /** Para a leitura e libera recursos (GATT, protocolo, jobs). Idempotente. */
    fun stop()
}
