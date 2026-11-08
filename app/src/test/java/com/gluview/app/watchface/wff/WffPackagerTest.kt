package com.gluview.app.watchface.wff

import com.android.apksig.ApkVerifier
import com.gluview.app.watchface.WatchFaceDesign
import com.gluview.app.watchface.WatchFacePresets
import com.gluview.app.watchface.WearableCatalog
import com.google.android.wearable.watchface.validator.client.DwfValidatorFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** W2 do plano 16: montar, assinar e validar o APK do visor como o celular faz. */
@RunWith(RobolectricTestRunner::class)
class WffPackagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val template = File("src/main/assets/wff/visor_template.apk")
    // Chave de teste gerada na hora com o keytool do próprio JDK (o repositório ignora arquivos de
    // chave de propósito — nenhuma chave, nem de teste, vai para o Git).
    private val keyPair: Pair<PrivateKey, X509Certificate> by lazy {
        val ks = File(tmp.root, "test-key.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(keytool, "-genkeypair", "-storetype", "PKCS12", "-keystore", ks.path, "-alias", "wf",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-storepass", "test-only", "-keypass", "test-only",
            "-dname", "CN=GluView watch face TEST ONLY").redirectErrorStream(true).start()
        check(p.waitFor() == 0) { p.inputStream.bufferedReader().readText() }
        KeyStore.getInstance("PKCS12").run {
            ks.inputStream().use { load(it, "test-only".toCharArray()) }
            (getKey("wf", "test-only".toCharArray()) as PrivateKey) to (getCertificate("wf") as X509Certificate)
        }
    }

    private fun build(xml: String): File {
        val unsigned = File(tmp.root, "u-${System.nanoTime()}.apk")
        template.inputStream().use { WffPackager.repack(it, xml, unsigned) }
        val signed = File(tmp.root, "visor-${System.nanoTime()}.apk")
        WffPackager.sign(unsigned, signed, keyPair.first, keyPair.second)
        return signed
    }

    private fun presetXml(watchId: String = "galaxy_watch_6_44"): String {
        val watch = WearableCatalog.find(watchId)
        val preset = WatchFacePresets.all.first { it.id == "glucose_focus" }
        return WffCompiler.compile(WatchFaceDesign(wearable = watch, components = preset.build(watch)),
            WffCompiler.Options(appPackage = "com.gluview.app")).xml
    }

    @Test fun trocaSoOXml_eDescartaAssinaturaVelha() {
        val apk = build(presetXml())
        assertEquals(presetXml(), String(WffPackager.readEntry(apk, WffPackager.WATCHFACE_ENTRY)!!))
        assertNotNull(WffPackager.readEntry(apk, "resources.arsc"))
        assertNotNull(WffPackager.readEntry(apk, "AndroidManifest.xml"))
        java.util.zip.ZipFile(apk).use { z ->
            val names = z.entries().toList().map { it.name }
            assertTrue(names.toString(), names.all { it == "AndroidManifest.xml" || it == "resources.arsc" || it.startsWith("res/") })
        }
    }

    @Test fun assinaturaV2Valida_eArscSemCompressaoAlinhado() {
        val apk = build(presetXml())
        val v = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(33).build().verify()
        assertTrue(v.errors.toString(), v.isVerified)
        assertTrue(v.isVerifiedUsingV2Scheme)
        java.util.zip.ZipFile(apk).use { assertEquals(java.util.zip.ZipEntry.STORED, it.getEntry("resources.arsc").method) }
        assertEquals(0L, dataOffset(apk, "resources.arsc") % 4)
    }

    @Test fun pontaAPonta_validadorOficialAprovaEDaToken() {
        for (watchId in listOf("galaxy_watch_6_44", "pixel_watch_2")) {
            val r = DwfValidatorFactory.create().validate(build(presetXml(watchId)), "com.gluview.app")
            assertEquals(r.failures().map { "${it.name()}: ${it.failureMessage()}" }.toString(), 0, r.failures().size)
            assertFalse(r.validationToken().isNullOrBlank())
        }
    }

    @Test fun pacoteErrado_oValidadorRecusa() {
        // O token vale para o app que instala; com outro pacote, o validador reprova.
        val r = DwfValidatorFactory.create().validate(build(presetXml()), "com.outro.app")
        assertTrue(r.failures().isNotEmpty())
    }

    /** Offset dos dados de uma entrada (cabeçalho local) — o Android exige arsc alinhado em 4. */
    private fun dataOffset(apk: File, name: String): Long = RandomAccessFile(apk, "r").use { f ->
        val len = f.length()
        var eocd = len - 22
        while (eocd >= 0) { f.seek(eocd); if (Integer.reverseBytes(f.readInt()) == 0x06054b50) break; eocd-- }
        f.seek(eocd + 10); val count = java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xffff
        f.seek(eocd + 16); var p = Integer.reverseBytes(f.readInt()).toLong() and 0xffffffffL
        repeat(count) {
            f.seek(p + 28)
            val nl = java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xffff
            val el = java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xffff
            val cl = java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xffff
            f.seek(p + 42); val local = Integer.reverseBytes(f.readInt()).toLong() and 0xffffffffL
            val bytes = ByteArray(nl); f.seek(p + 46); f.readFully(bytes)
            if (String(bytes) == name) {
                f.seek(local + 26)
                val lnl = java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xffff
                val lel = java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xffff
                return local + 30 + lnl + lel
            }
            p += 46 + nl + el + cl
        }
        error("entrada $name não encontrada")
    }
}
