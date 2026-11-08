package com.gluview.app.watchface.wff

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.gluview.app.watchface.WatchFaceComponent
import com.gluview.app.watchface.WatchFaceDesign
import com.google.android.wearable.watchface.validator.client.DwfValidatorFactory
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/**
 * Visor do construtor → APK pronto para o Watch Face Push (plano 16, W2), tudo no celular:
 * traduz ([WffCompiler]) → confere ([WffRules]) → monta a partir do modelo ([WffPackager]) → assina
 * com a chave do aparelho → valida com o validador oficial (que devolve o token exigido na instalação).
 * Um APK inválido nunca sai daqui.
 */
class WatchFaceBuilder(private val context: Context) {

    sealed interface Result {
        data class Built(val apk: File, val token: String, val unsupported: List<WatchFaceComponent>) : Result
        data class Rejected(val problems: List<String>) : Result
    }

    fun build(design: WatchFaceDesign, options: WffCompiler.Options): Result {
        val compiled = WffCompiler.compile(design, options)
        WffRules.check(compiled.xml).takeIf { it.isNotEmpty() }?.let { return Result.Rejected(it) }

        val dir = File(context.cacheDir, "wff").apply { mkdirs() }
        val unsigned = File(dir, "visor-unsigned.apk")
        val signed = File(dir, "visor.apk")
        context.assets.open(TEMPLATE_ASSET).use { WffPackager.repack(it, compiled.xml, unsigned) }
        val (key, cert) = signingKey()
        WffPackager.sign(unsigned, signed, key, cert)
        unsigned.delete()

        val validation = DwfValidatorFactory.create().validate(signed, context.packageName)
        if (validation.failures().isNotEmpty() || validation.validationToken().isNullOrBlank()) {
            val problems = validation.failures().map { "${it.name()}: ${it.failureMessage()}" }
            Log.w(TAG, "Visor reprovado no validador: $problems")
            return Result.Rejected(problems.ifEmpty { listOf("validador sem token") })
        }
        return Result.Built(signed, validation.validationToken(), compiled.unsupported)
    }

    /**
     * Chave RSA própria dos visores no Android Keystore (diferente da chave do app — exigência do
     * Watch Face Push). É criada uma vez e reaproveitada: trocar de chave impediria atualizar o visor
     * já instalado (aí o relógio remove e instala de novo).
     */
    private fun signingKey(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val until = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setCertificateSubject(X500Principal("CN=GluView watch face"))
                        .setCertificateSerialNumber(BigInteger.ONE)
                        .setCertificateNotAfter(until)
                        .build()
                )
            }.generateKeyPair()
        }
        val key = ks.getKey(KEY_ALIAS, null) as PrivateKey
        val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate
        return key to cert
    }

    companion object {
        private const val TAG = "WatchFaceBuilder"
        const val TEMPLATE_ASSET = "wff/visor_template.apk"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "gluview_watchface_push"
    }
}
