package com.gluview.app.data.update

import java.util.Locale

/**
 * Regras puras de confiança do atualizador (plano OTA, seções 5.3 e 5.7). Sem Android: testáveis
 * em JUnit puro.
 */
object UpdateTrust {

    /**
     * Âncora de confiança: SHA-256 dos certificados que podem assinar o GluView. O APK baixado só
     * é instalado se o certificado dele (ou algum do histórico de rotação) estiver aqui. O
     * certSha256 do manifest.json é só informativo — um servidor comprometido controlaria esse campo.
     *
     *  - A: chave de debug do PC de publicação, que assina os aparelhos instalados hoje.
     *  - B: chave de release (etapa E11 do plano). PRECISA entrar aqui numa versão publicada
     *    ANTES da rotação, senão o app recusaria o próprio update A→B.
     */
    val TRUSTED_CERT_SHA256: Set<String> = setOf(
        "e678b39d85151bd06f3206b6bffa7f2b1bdbd65f2cb4e2c8dbe8a5584bd8c796", // A
        "eceb6c6bd9128959483cdf9dcb5628efc3513bea73db8613024769bb01b5e034", // B (lineage A→B em signing/lineage.bin)
    )

    /** Hosts de onde o APK pode vir (URL assinada do GitHub). Qualquer outro é recusado, mesmo
     *  que o servidor mande — limita o estrago de um GluSync comprometido. */
    private val ALLOWED_DOWNLOAD_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    const val APP_PACKAGE = "com.gluview.app"

    fun isAllowedDownloadUrl(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host in ALLOWED_DOWNLOAD_HOSTS
    }

    fun isValidSha256(hex: String?): Boolean = hex != null && Regex("^[0-9a-f]{64}$").matches(hex)

    /** Confia se o signatário atual ou algum do histórico de rotação está na lista. Quem tivesse
     *  uma chave confiável poderia assinar um APK direto, então aceitar o histórico não enfraquece. */
    fun isTrusted(certSha256s: Collection<String>): Boolean =
        certSha256s.any { it.lowercase(Locale.ROOT) in TRUSTED_CERT_SHA256 }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Espaço necessário: o arquivo baixado e a cópia na sessão do PackageInstaller coexistem por
     *  um instante, mais folga para o próprio Android extrair as .so (useLegacyPackaging). */
    fun requiredFreeBytes(sizeBytes: Long): Long = sizeBytes * 3 + 50L * 1024 * 1024
}
