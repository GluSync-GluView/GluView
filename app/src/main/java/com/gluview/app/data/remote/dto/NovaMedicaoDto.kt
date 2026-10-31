package com.gluview.app.data.remote.dto

/**
 * Evento SignalR "NovaMedicao".
 *
 * ATENÇÃO: o servidor emite este evento com FORMATOS diferentes conforme a origem da leitura.
 * `GravarLeitura` e `/glucose/ingest` transmitem um GlucoseEntry ({date, sgv, direction, device,
 * type}) — que NÃO tem os campos `arrow` nem `timestamp` — enquanto os serviços de sync
 * (Nightscout/Dexcom/LibreLinkUp) incluem `arrow`. Por isso `arrow` é nulável: o Gson do cliente
 * o deixa null quando ausente, e o consumidor aplica fallback ("") em vez de estourar NPE ao
 * montar um GlucoseReading (cujo campo `arrow` é não-nulo). O `timestamp` também pode faltar e é
 * tratado no consumidor com `.orEmpty()` (nunca é confiável para o horário real da medição —
 * o app usa o relógio local, ver GluSyncService.startSignalR).
 */
data class NovaMedicaoDto(
    val sgv: Double,
    val direction: String,
    val arrow: String? = null,
    val timestamp: String = "",
    /** Hora da medição (GlucoseEntry.date, epoch ms) quando a origem manda; 0 = não veio (L8). */
    val date: Long = 0L
)
