package com.gluview.app.watchface.wff

import com.android.apksig.ApkSigner
import java.io.File
import java.io.InputStream
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Monta o APK do visor no CELULAR (plano 16, W2) sem compilar recursos: parte do APK-modelo dos
 * assets (gerado por tools/wff/build-template.sh) e troca só `res/raw/watchface.xml` — recurso raw
 * vai byte a byte no APK, então nada de aapt2/Pack no aparelho. Depois assina com `apksig`.
 *
 * Regras do Watch Face Push respeitadas aqui: só manifest/arsc/res/META-INF no APK; `resources.arsc`
 * sem compressão (o Android exige para targetSdk ≥ 30) e alinhado em 4 bytes (o ApkSigner alinha
 * entradas sem compressão); assinatura v2 (minSdk 33 dispensa a v1).
 */
object WffPackager {
    const val WATCHFACE_ENTRY = "res/raw/watchface.xml"
    private val ALLOWED = Regex("""^(AndroidManifest\.xml|resources\.arsc|res/.+)$""")

    /** Copia o modelo trocando o XML do mostrador; descarta assinatura antiga e qualquer arquivo fora das regras. */
    fun repack(template: InputStream, watchfaceXml: String, unsignedOut: File) {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(template).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.isDirectory && ALLOWED.matches(e.name)) entries[e.name] = zin.readBytes()
            }
        }
        require(WATCHFACE_ENTRY in entries) { "modelo sem $WATCHFACE_ENTRY" }
        require("AndroidManifest.xml" in entries && "resources.arsc" in entries) { "modelo incompleto" }
        entries[WATCHFACE_ENTRY] = watchfaceXml.toByteArray(Charsets.UTF_8)

        ZipOutputStream(unsignedOut.outputStream().buffered()).use { zout ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                if (name == "resources.arsc") {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                entry.time = 315_532_800_000L // 1980-01-01, igual ao aapt2 (APK reproduzível)
                zout.putNextEntry(entry)
                zout.write(data)
                zout.closeEntry()
            }
        }
    }

    /** Assina (v2) com a chave do aparelho. A MESMA chave sempre: o update do slot exige o mesmo assinante. */
    fun sign(unsigned: File, signedOut: File, key: PrivateKey, cert: X509Certificate) {
        val signer = ApkSigner.SignerConfig.Builder("GLUVIEW", key, listOf(cert)).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(unsigned)
            .setOutputApk(signedOut)
            .setMinSdkVersion(33)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .build()
            .sign()
    }

    /** Lê uma entrada de um APK/zip (para testes e diagnóstico). */
    fun readEntry(apk: File, name: String): ByteArray? = ZipInputStream(apk.inputStream()).use { zin ->
        generateSequence { zin.nextEntry }.firstOrNull { it.name == name }?.let { zin.readBytes() }
    }
}
