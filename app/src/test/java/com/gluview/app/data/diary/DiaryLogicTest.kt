package com.gluview.app.data.diary

import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.repository.CloudAccessRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Plano 17, Fase 1 — regras puras do diário. */
class DiaryLogicTest {

    @Test fun `resumo do dia separa rapida, basal e mista e ignora apagados`() {
        val s = DaySummary.of(listOf(
            EventLogEntity(type = "meal", value = 45.0, kcal = 400.0),
            EventLogEntity(type = "meal", value = 30.0),
            EventLogEntity(type = "insulin", value = 4.5, subtype = "rapid"),
            EventLogEntity(type = "insulin", value = 18.0, subtype = "basal"),
            EventLogEntity(type = "insulin", value = 10.0, subtype = "mixed"),
            // Registro antigo (antes da 14): insulina sem tipo conta como rápida.
            EventLogEntity(type = "insulin", value = 2.0),
            EventLogEntity(type = "exercise", value = 30.0),
            EventLogEntity(type = "meal", value = 99.0, deleted = true)
        ))
        assertEquals(75.0, s.carbsG, 0.0)
        assertEquals(400.0, s.kcal, 0.0)
        assertEquals(6.5, s.rapidU, 0.0)
        assertEquals(18.0, s.basalU, 0.0)
        assertEquals(10.0, s.mixedU, 0.0)
        assertEquals(30.0, s.exerciseMin, 0.0)
        assertEquals(7, s.entries)
    }

    @Test fun `itens da refeicao vao e voltam do JSON`() {
        val items = listOf(MealItem("b:rice", "Arroz", 100.0, 28.2, 130.0, 2.7, 0.3, "4 × colher"), MealItem("", "Avulso", 0.0, 10.0))
        assertEquals(items, MealItem.decode(MealItem.encode(items)))
        assertEquals(emptyList<MealItem>(), MealItem.decode(""))
        assertEquals(emptyList<MealItem>(), MealItem.decode("lixo"))
        assertEquals(38.2, MealTotals.of(items).carbsG, 1e-9)
    }

    @Test fun `faixas de cetona`() {
        assertEquals(KetoneLevel.NORMAL, ketoneLevel(KetoneMethod.BLOOD, 0.4))
        assertEquals(KetoneLevel.ATTENTION, ketoneLevel(KetoneMethod.BLOOD, 0.6))
        assertEquals(KetoneLevel.RISK, ketoneLevel(KetoneMethod.BLOOD, 2.0))
        assertEquals(KetoneLevel.URGENT, ketoneLevel(KetoneMethod.BLOOD, 3.1))
        assertEquals(KetoneLevel.NORMAL, ketoneLevel(KetoneMethod.URINE, 1.0))
        assertEquals(KetoneLevel.RISK, ketoneLevel(KetoneMethod.URINE, 3.0))
        assertEquals("++", KetoneUrine.label(3.0))
    }

    @Test fun `base de alimentos embutida le, busca sem acento e calcula a porcao`() {
        val json = File("src/main/assets/diary/foods_base.json").readText()
        val pt = FoodCatalog.parse(json, "pt")
        val en = FoodCatalog.parse(json, "en")
        assertTrue(pt.size >= 80)
        assertEquals(pt.size, en.size)
        assertEquals(pt.size, pt.map { it.key }.toSet().size) // chaves únicas
        assertTrue(pt.all { it.carbsPer100 in 0.0..100.0 && it.kcalPer100 >= 0 })
        val feijao = FoodCatalog.search(pt, "feijao")
        assertTrue(feijao.isNotEmpty() && feijao.all { it.name.startsWith("Feijão") })
        // Começa com o termo vem antes de só conter.
        assertEquals("Arroz branco cozido", FoodCatalog.search(pt, "arroz br").first().name)
        val rice = pt.first { it.key == "b:rice_white_cooked" }
        val item = rice.itemFor(150.0, "6 × colher")
        assertEquals(42.3, item.carbsG, 1e-9)
        assertEquals(195.0, item.kcal, 1e-9)
        assertEquals("White rice, cooked", en.first { it.key == rice.key }.name)
    }

    @Test fun `busca vazia mostra os mais usados primeiro`() {
        val a = FoodItem("b:a", "Abacate", 8.5, 160.0, 2.0, 14.7, emptyList(), false)
        val b = FoodItem("u:1", "Bolo da vó", 50.0, 300.0, 5.0, 10.0, emptyList(), true, useCount = 5, lastUsedAt = 10)
        assertEquals(listOf(b, a), FoodCatalog.search(listOf(a, b), ""))
    }

    @Test fun `treatment do GluView vai e volta sem perder campos`() {
        val e = EventLogEntity(occurredAt = 1_700_000_000_000, type = "insulin", value = 18.0, subtype = "basal",
            itemName = "Tresiba", notes = "noite", sgvAtEvent = 140.0, updatedAt = 1_700_000_100_000, uuid = "u-1")
        val t = DiaryTreatmentMapper.toTreatment(e, "Insulina basal · Tresiba — 18 U")
        // Basal NÃO vai no campo insulin (o Nightscout contaria como bolus).
        assertEquals("Note", t.getString("eventType"))
        assertFalse(t.has("insulin"))
        assertEquals("GluView", t.getString("enteredBy"))
        t.put("_id", "abc")
        val back = DiaryTreatmentMapper.fromTreatment(JSONObject(t.toString()), "a@b.c")!!
        assertEquals(e.copy(id = 0, source = "cloud", ownerEmail = "a@b.c", remoteId = "abc", dirty = false), back)
    }

    @Test fun `tipos viram o evento Nightscout certo`() {
        fun ev(type: String, sub: String = "") = DiaryTreatmentMapper.toTreatment(EventLogEntity(type = type, value = 5.0, subtype = sub), "")
        assertEquals("Carb Correction", ev("meal").getString("eventType"))
        assertEquals(5.0, ev("meal").getDouble("carbs"), 0.0)
        assertEquals("Correction Bolus", ev("insulin", "rapid").getString("eventType"))
        assertEquals(5.0, ev("insulin", "rapid").getDouble("insulin"), 0.0)
        assertEquals("BG Check", ev("fingerstick").getString("eventType"))
        assertEquals("Exercise", ev("exercise").getString("eventType"))
        assertEquals("Note", ev("ketone").getString("eventType"))
    }

    @Test fun `treatment de outro app nao entra no diario`() {
        assertNull(DiaryTreatmentMapper.fromTreatment(JSONObject("""{"eventType":"Meal Bolus","carbs":30,"_id":"x"}"""), ""))
        assertNull(DiaryTreatmentMapper.fromTreatment(JSONObject("""{"gluview":{"uuid":"u","type":"desconhecido","occurredAt":1}}"""), ""))
        assertNotNull(DiaryTreatmentMapper.fromTreatment(JSONObject("""{"gluview":{"uuid":"u","type":"note","occurredAt":1}}"""), ""))
    }

    @Test fun `GluSync so entra com conta, sem modo local, liberado e fora do bloqueio`() {
        val ok = CloudAccessRepository.STATE_APPROVED
        assertTrue(DiaryCloudGate.allowed(true, false, ok, 0, 100))
        assertFalse(DiaryCloudGate.allowed(false, false, ok, 0, 100))      // sem conta
        assertFalse(DiaryCloudGate.allowed(true, true, ok, 0, 100))        // modo 100% local
        assertFalse(DiaryCloudGate.allowed(true, false, "pending", 0, 100)) // sem liberação
        assertFalse(DiaryCloudGate.allowed(true, false, "", 0, 100))        // nunca consultado
        assertFalse(DiaryCloudGate.allowed(true, false, ok, 200, 100))      // servidor recusou há pouco
    }

    @Test fun `backup do registro vai e volta`() {
        val e = EventLogEntity(occurredAt = 5, type = "meal", value = 30.0, kcal = 200.0, itemsJson = "[]", uuid = "x", updatedAt = 9)
        val back = com.gluview.app.data.diary.DiaryBackup.entryFrom(
            JSONObject(com.gluview.app.data.diary.DiaryBackup.entryJson(e).toString()), "o")!!
        assertEquals(e.copy(ownerEmail = "o", dirty = true), back)
    }
}
