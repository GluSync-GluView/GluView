package com.gluview.app.watchface.apply

import com.gluview.app.watchface.WatchFaceDesign

/** Resultado de uma tentativa de aplicar um visor a um wearable. */
sealed interface ApplyResult {
    data object Success : ApplyResult
    /** Falta configuração do usuário (ex.: auth key/MAC da Mi Band). */
    /** [messageRes]: frase própria do applier (ex.: Wear OS); null = frase genérica da Mi Band. */
    data class NeedsSetup(val message: String, @androidx.annotation.StringRes val messageRes: Int? = null) : ApplyResult
    /** Não há estratégia de envio para este wearable ainda. */
    data class Unsupported(val message: String, @androidx.annotation.StringRes val messageRes: Int? = null) : ApplyResult
    /** Estrutura pronta, mas o envio completo ainda está em desenvolvimento. */
    data class InProgress(val message: String) : ApplyResult
    data class Failure(val message: String) : ApplyResult
}

/**
 * Estratégia de envio de um visor para um TIPO de wearable. Cada plataforma implementa a sua:
 *   - Mi Band / Huami  → BLE direto do celular (upload de watchface), estilo WatchDrip;
 *   - ZeppOS (Amazfit) → app companheiro no relógio (futuro);
 *   - Wear OS          → app/face no relógio via Data Layer (futuro).
 *
 * O editor e o gerenciador NÃO conhecem os detalhes — pedem [apply] ao [WatchFaceApplierRegistry],
 * que escolhe a estratégia pelo wearable do design. Adicionar uma plataforma = somar um applier.
 */
interface WatchFaceApplier {
    val id: String
    val displayName: String
    fun supports(design: WatchFaceDesign): Boolean
    suspend fun apply(design: WatchFaceDesign): ApplyResult
}
