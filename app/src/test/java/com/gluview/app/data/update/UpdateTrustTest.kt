package com.gluview.app.data.update

import com.gluview.app.data.remote.dto.UpdateDownloadLinkDto
import com.gluview.app.data.remote.dto.UpdateLatestDto
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras de confiança do atualizador e o contrato JSON com o GluSync (plano OTA, 5.2 e 5.7). */
class UpdateTrustTest {

    private val keyA = "e678b39d85151bd06f3206b6bffa7f2b1bdbd65f2cb4e2c8dbe8a5584bd8c796"

    @Test fun certificado_soAceitaListaEmbutida_inclusiveNoHistoricoDeRotacao() {
        assertTrue(UpdateTrust.isTrusted(listOf(keyA)))
        assertTrue(UpdateTrust.isTrusted(listOf(keyA.uppercase())))
        assertTrue("histórico A→B", UpdateTrust.isTrusted(listOf(keyA, "b".repeat(64))))
        assertFalse(UpdateTrust.isTrusted(listOf("0".repeat(64))))
        assertFalse(UpdateTrust.isTrusted(emptyList()))
    }

    @Test fun urlDeDownload_soHttpsEmHostPermitido() {
        assertTrue(UpdateTrust.isAllowedDownloadUrl("https://release-assets.githubusercontent.com/github-production-release-asset/1/2?jwt=x"))
        assertTrue(UpdateTrust.isAllowedDownloadUrl("https://objects.githubusercontent.com/a"))
        assertFalse(UpdateTrust.isAllowedDownloadUrl("http://release-assets.githubusercontent.com/a"))
        assertFalse(UpdateTrust.isAllowedDownloadUrl("https://evil.example.com/GluView.apk"))
        assertFalse(UpdateTrust.isAllowedDownloadUrl("https://release-assets.githubusercontent.com.evil.example/a"))
        assertFalse(UpdateTrust.isAllowedDownloadUrl("file:///sdcard/GluView.apk"))
        assertFalse(UpdateTrust.isAllowedDownloadUrl("não é url"))
    }

    @Test fun sha256_exigeHexMinusculoDe64() {
        assertTrue(UpdateTrust.isValidSha256(keyA))
        assertFalse(UpdateTrust.isValidSha256(keyA.uppercase()))
        assertFalse(UpdateTrust.isValidSha256("abc"))
        assertFalse(UpdateTrust.isValidSha256(null))
    }

    @Test fun espacoNecessario_cobreArquivoMaisCopiaNaSessao() {
        assertTrue(UpdateTrust.requiredFreeBytes(25_000_000) > 2 * 25_000_000L)
    }

    @Test fun latest_parseDoJsonRealDoServidor() {
        // Formato devolvido pelo GluSync (AppUpdateLatestResponse), inclusive com a flag desligada.
        val on = Gson().fromJson("""{"updateAvailable":true,"recommended":true,"security":false,"versionCode":437760,
            "versionName":"1.0.2","minSdk":26,"sizeBytes":25465634,"sha256":"$keyA","publishedAt":"2026-11-01T12:00:00Z"}""",
            UpdateLatestDto::class.java)
        assertTrue(on.updateAvailable); assertEquals(437_760L, on.versionCode); assertEquals("1.0.2", on.versionName)
        val off = Gson().fromJson("""{"updateAvailable":false,"recommended":false,"security":false,"versionCode":null,
            "versionName":null,"minSdk":null,"sizeBytes":null,"sha256":null,"publishedAt":null}""", UpdateLatestDto::class.java)
        assertFalse(off.updateAvailable); assertNull(off.versionCode)
    }

    @Test fun downloadLink_parseDoJsonRealDoServidor() {
        val link = Gson().fromJson("""{"url":"https://release-assets.githubusercontent.com/x?jwt=a.b.c",
            "expiresAt":"2026-11-01T12:05:00Z","versionCode":437760,"versionName":"1.0.2","sha256":"$keyA",
            "sizeBytes":25465634,"notes":{"pt":"Notas","en":"Notes"}}""", UpdateDownloadLinkDto::class.java)
        assertEquals(437_760L, link.versionCode); assertEquals("Notas", link.notes?.pt)
        assertTrue(UpdateTrust.isAllowedDownloadUrl(link.url))
    }
}
