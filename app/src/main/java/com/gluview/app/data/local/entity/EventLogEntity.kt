package com.gluview.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Registro do diário (plano 17, F1.1). A tabela continua `event_log` (nasceu na versão 2) e ganhou
 * campos próprios na versão 14 — o que já existia segue valendo do mesmo jeito.
 *
 * [value] é o número principal de cada tipo (ver [com.gluview.app.data.diary.DiaryType]):
 * carboidrato (g) na refeição, unidades na insulina, minutos no exercício, mmol/L (sangue) ou nível
 * 0–4 (urina) na cetona, dose no remédio e mg/dL na ponta de dedo. Nota não tem número.
 *
 * O diário é do aparelho (GluView first): a nuvem só entra quando a conta tem a liberação — os
 * campos [remoteId], [dirty] e [deleted] existem para essa sincronização opcional.
 */
@Entity(
    tableName = "event_log",
    indices = [Index(value = ["uuid"], unique = true), Index(value = ["occurredAt"])]
)
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Long   = System.currentTimeMillis(),
    /** meal | insulin | exercise | note | ketone | medication | fingerstick */
    val type: String,
    val notes: String      = "",
    val value: Double      = 0.0,
    /** Glicemia do sensor no horário do registro (0 = sem leitura). */
    val sgvAtEvent: Double = 0.0,
    /** Identificador estável: não muda ao editar, casa backup, nuvem e (no futuro) o relógio. */
    val uuid: String       = UUID.randomUUID().toString(),
    /** Insulina: rapid/basal/mixed · cetona: blood/urine · exercício: light/moderate/intense. */
    val subtype: String    = "",
    /** Nome da insulina, do remédio ou do tipo de exercício. */
    val itemName: String   = "",
    /** Unidade do remédio (mg, comprimido…). */
    val unit: String       = "",
    val kcal: Double       = 0.0,
    val proteinG: Double   = 0.0,
    val fatG: Double       = 0.0,
    /** Itens da refeição (JSON, ver [com.gluview.app.data.diary.MealItem]); vazio = carboidrato avulso. */
    val itemsJson: String  = "",
    /** manual | diagnostic | reminder | cloud | backup */
    val source: String     = SOURCE_MANUAL,
    /** Conta dona do registro ("" = modo local, reclamado pela 1ª conta que entrar). */
    val ownerEmail: String = "",
    val updatedAt: Long    = System.currentTimeMillis(),
    /** Apagado aguardando a nuvem saber (só quando já foi enviado). */
    val deleted: Boolean   = false,
    /** _id do treatment no GluSync, quando enviado. */
    val remoteId: String?  = null,
    /** Mudou desde o último envio à nuvem. */
    val dirty: Boolean     = true
) {
    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_DIAGNOSTIC = "diagnostic"
        const val SOURCE_REMINDER = "reminder"
        const val SOURCE_CLOUD = "cloud"
        const val SOURCE_BACKUP = "backup"
    }
}
