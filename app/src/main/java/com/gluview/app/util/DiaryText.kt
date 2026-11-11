package com.gluview.app.util

import android.content.Context
import com.gluview.app.R
import com.gluview.app.data.diary.DiaryType
import com.gluview.app.data.diary.ExerciseIntensity
import com.gluview.app.data.diary.InsulinKind
import com.gluview.app.data.diary.KetoneMethod
import com.gluview.app.data.diary.KetoneUrine
import com.gluview.app.data.local.entity.EventLogEntity
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Textos do diário fora do Compose (nuvem, PDF, notificações) — no idioma do [res] e com a
 * unidade de glicemia do usuário. A tela usa as mesmas funções.
 */
class DiaryText(private val res: Context, private val unit: GlucoseUnit = GlucoseUnit.MG_DL) {
    private fun s(id: Int, vararg args: Any) = res.getString(id, *args)
    private val locale: Locale get() = res.resources.configuration.locales[0] ?: Locale.getDefault()

    fun amount(v: Double): String = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale)).format(v)

    fun typeLabel(type: DiaryType): String = s(typeLabelRes(type))

    fun insulinKind(kind: InsulinKind): String = s(when (kind) {
        InsulinKind.RAPID -> R.string.diary_insulin_rapid
        InsulinKind.BASAL -> R.string.diary_insulin_basal
        InsulinKind.MIXED -> R.string.diary_insulin_mixed
    })

    fun intensity(i: ExerciseIntensity): String = s(when (i) {
        ExerciseIntensity.LIGHT -> R.string.diary_exercise_light
        ExerciseIntensity.MODERATE -> R.string.diary_exercise_moderate
        ExerciseIntensity.INTENSE -> R.string.diary_exercise_intense
    })

    /** Título da linha: "Insulina rápida · Humalog". */
    fun title(e: EventLogEntity): String {
        val type = DiaryType.of(e.type)
        return when (type) {
            DiaryType.INSULIN -> listOf(insulinKind(InsulinKind.of(e.subtype)), e.itemName).filter { it.isNotBlank() }
                .joinToString(" · ").let { s(R.string.diary_insulin_title, it) }
            DiaryType.MEDICATION -> listOf(typeLabel(type), e.itemName).filter { it.isNotBlank() }.joinToString(" · ")
            DiaryType.EXERCISE -> listOf(typeLabel(type), e.itemName).filter { it.isNotBlank() }.joinToString(" · ")
            DiaryType.KETONE -> s(if (KetoneMethod.of(e.subtype) == KetoneMethod.URINE) R.string.diary_ketone_urine_title
                                  else R.string.diary_ketone_blood_title)
            else -> typeLabel(type)
        }
    }

    /** Valor principal com unidade: "45 g de carboidrato · 520 kcal", "4 U", "120 mg/dL". */
    fun value(e: EventLogEntity): String = when (DiaryType.of(e.type)) {
        DiaryType.MEAL -> buildList {
            add(s(R.string.diary_value_carbs, amount(e.value)))
            if (e.kcal > 0) add(s(R.string.diary_value_kcal, amount(e.kcal)))
        }.joinToString(" · ")
        DiaryType.INSULIN -> s(R.string.diary_value_units, amount(e.value))
        DiaryType.FINGERSTICK -> formatGlucoseWithUnit(e.value, unit)
        DiaryType.KETONE -> if (KetoneMethod.of(e.subtype) == KetoneMethod.URINE) KetoneUrine.label(e.value)
                            else s(R.string.diary_value_mmol, amount(e.value))
        DiaryType.MEDICATION -> if (e.value > 0) listOf(amount(e.value), e.unit).filter { it.isNotBlank() }.joinToString(" ") else ""
        DiaryType.EXERCISE -> buildList {
            if (e.value > 0) add(s(R.string.diary_value_minutes, amount(e.value)))
            ExerciseIntensity.of(e.subtype)?.let { add(intensity(it)) }
        }.joinToString(" · ")
        DiaryType.NOTE -> ""
    }

    /** Uma linha só (nota na nuvem, PDF, notificação): "Insulina basal · Tresiba — 18 U — obs". */
    fun describe(e: EventLogEntity): String =
        listOf(title(e), value(e), e.notes.trim()).filter { it.isNotBlank() }.joinToString(" — ")

    companion object {
        fun typeLabelRes(type: DiaryType): Int = when (type) {
            DiaryType.MEAL -> R.string.diary_type_meal
            DiaryType.INSULIN -> R.string.diary_type_insulin
            DiaryType.FINGERSTICK -> R.string.diary_type_fingerstick
            DiaryType.KETONE -> R.string.diary_type_ketone
            DiaryType.MEDICATION -> R.string.diary_type_medication
            DiaryType.EXERCISE -> R.string.diary_type_exercise
            DiaryType.NOTE -> R.string.diary_type_note
        }
    }
}
