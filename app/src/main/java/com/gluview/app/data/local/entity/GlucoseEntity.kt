package com.gluview.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "glucose_readings")
data class GlucoseEntity(
    @PrimaryKey val dateMs: Long,
    val sgvMgDl: Double,
    val direction: String,
    val arrow: String,
    val status: String,
    val delta: Int,
    val isStale: Boolean,
    val minutesAgo: Int,
    val fetchedAt: Long = System.currentTimeMillis(),
    // F3/T23 — dados do sensor BLE (nulo para leituras GluSync)
    val rawValue: Double? = null,     // mmol/L bruto do algoritmo antes do fator do usuário
    val calibFactor: Double? = null,  // fator de calibração aplicado (1.0 = sem ajuste)
    val sensorMac: String? = null,    // MAC do sensor BLE que gerou a leitura
    val sensorAgeMin: Int? = null,    // minutos de vida do sensor no momento da leitura (index)
    // Modos de leitura Sibionics (Ajustes > Sensor): true = a .so mudou de valor nesta leitura
    // ("confirmada"); false = repetiu o valor anterior ("provisória"); null = fonte sem o conceito
    // (GluSync/Libre2/v1.1.0). Sempre gravado quando disponível, independente do modo escolhido.
    val confirmed: Boolean? = null,
    // Conta logada no momento da gravação (AppPreferences.userEmail) — isola o cache local por
    // conta quando mais de uma é usada no mesmo aparelho (ex.: conta admin de teste). Linhas
    // anteriores a essa coluna nascem "" e são reclamadas pela 1ª conta que logar depois
    // (GlucoseDao.claimUnownedRows), preservando o histórico já existente.
    val ownerEmail: String = ""
)
