package com.gluview.app.data.libre3

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.NfcV
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ativação / assunção do FreeStyle Libre 3 por NFC — porte do Juggluco (GPLv3) `Libre3.java`,
 * `libre3/NFC.java` e `AlgNfcV.wholenfccmd`:
 *  1. `02 A1 7A` → informação do sensor (serial, estado, aquecimento, duração);
 *  2. `02 A0 7A` (sensor novo) ou `02 A8 7A` (sensor já ativo) + hora + ID de conta + CRC;
 *  3. resposta: endereço Bluetooth, PIN e hora de ativação — guardados para o Bluetooth.
 *
 * Sensor ativado no app oficial só aceita ser assumido com o ID da conta LibreView usada nele
 * (resposta de erro 0xB1 → [Outcome.WrongAccount]); o GluView usa o próprio ID sorteado
 * ([AppPreferences.libre3AccountIdOrCreate]) ou o informado pela pessoa.
 */
@Singleton
class Libre3NfcActivator @Inject constructor(
    private val preferences: AppPreferences,
    private val appLogger: AppLogger
) {
    data class Session(
        val serial: String,
        val mac: String,
        val pinHex: String,
        val activationMs: Long,
        val warmupMin: Int,
        val wearMin: Int,
        val wasNew: Boolean
    )

    sealed interface Outcome {
        data class Ok(val session: Session) : Outcome
        /** A etiqueta não respondeu como Libre 3 (pode ser Libre 2/2 Plus ou outra coisa). */
        data object NotLibre3 : Outcome
        /** Lingo: outra credencial (Juggluco LingoSKB), fora do escopo do GluView. */
        data object Lingo : Outcome
        data object WrongAccount : Outcome
        data object Ended : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private companion object {
        const val LOG = "LIBRE3_NFC"
        const val TIMEOUT_MS = 5_000L
        const val TRIES = 10
    }

    suspend fun activate(tag: Tag, accountIdOverride: Long? = null): Outcome {
        val first = transceive(tag, Libre3Protocol.NFC_FIRST_COMMAND)
            ?: return Outcome.Failed("o sensor não respondeu ao NFC (encoste de novo e segure parado)")
        val info = Libre3Protocol.parseNfcInfo(first) ?: return Outcome.NotLibre3
        appLogger.usage(LOG, "Sensor lido", mapOf("serial" to info.serial, "estado" to info.state,
            "produto" to info.productType, "aquecimentoMin" to info.warmupMin, "duracaoMin" to info.wearDurationMin))
        if (info.isLingo) return Outcome.Lingo
        val accountId = accountIdOverride ?: preferences.libre3AccountIdOrCreate()
        val nowSec = Math.round(System.currentTimeMillis() / 1000.0)
        val second = transceive(tag, Libre3Protocol.activationCommand(info, nowSec, accountId))
        return when (val r = Libre3Protocol.parseActivationResponse(second, info, nowSec)) {
            is Libre3Protocol.ActivationResult.Ok -> {
                val session = Session(
                    serial = info.serial,
                    mac = r.bleAddress,
                    pinHex = r.pin.joinToString("") { "%02x".format(it) },
                    activationMs = r.activationSec * 1000L,
                    warmupMin = info.warmupMin,
                    wearMin = info.wearDurationMin,
                    wasNew = info.needsActivation
                )
                preferences.saveLibre3Session(session.serial, session.mac, session.pinHex, session.activationMs,
                    session.warmupMin, session.wearMin)
                if (accountIdOverride != null) preferences.saveLibre3AccountId(accountIdOverride)
                appLogger.usage(LOG, if (session.wasNew) "Sensor ativado" else "Sensor assumido",
                    mapOf("serial" to session.serial, "mac" to session.mac))
                Outcome.Ok(session)
            }
            Libre3Protocol.ActivationResult.WrongAccount -> Outcome.WrongAccount
            Libre3Protocol.ActivationResult.SensorEnded -> Outcome.Ended
            is Libre3Protocol.ActivationResult.Failed -> Outcome.Failed(r.reason)
        }
    }

    /** Juggluco `wholenfccmdtimes`: até 10 tentativas, resposta boa = bit 0 do 1º byte zerado. */
    private fun transceive(tag: Tag, cmd: ByteArray): ByteArray? {
        repeat(TRIES) {
            val nfc = NfcV.get(tag) ?: return null
            try {
                nfc.connect()
                val end = System.currentTimeMillis() + TIMEOUT_MS
                repeat(TRIES) {
                    try {
                        val r = nfc.transceive(cmd)
                        if (r != null && r.isNotEmpty() && (r[0].toInt() and 1) == 0) return r
                    } catch (e: TagLostException) {
                        return null
                    } catch (e: SecurityException) {
                        return null
                    } catch (t: Throwable) {
                        if (System.currentTimeMillis() > end) return null
                    }
                }
                return null
            } catch (e: TagLostException) {
                return null
            } catch (t: Throwable) {
                appLogger.usage(LOG, "NFC: tentando de novo", mapOf("erro" to (t.message ?: t::class.simpleName.orEmpty())))
            } finally {
                runCatching { nfc.close() }
            }
        }
        return null
    }
}
