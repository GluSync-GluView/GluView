package com.gluview.app.data.dexcom

import com.eveningoutpost.dexdrip.plugin.IPluginDA

/**
 * PT-3 — o que fazer no rádio a cada passo da autenticação do G7, sem Bluetooth (testável).
 * Porte de `Ob1G5StateMachine.doCheckAuth2/doNext` do xDrip: o plugin EC-JPAKE (`jamorham.keks.Plugin`,
 * módulo `:keks`) decide as mensagens; aqui só se traduz para operações de GATT.
 *
 * Primeiro pareamento sem o app oficial (sensor ainda sem bond neste celular) exige certificados e a
 * chave privada do app oficial (partes A/B/C da libkeks), que o GluView não tem nem pode distribuir.
 * Por isso o caminho suportado é: o G7 já pareado pelo app oficial da Dexcom no mesmo celular, e o
 * GluView autentica com o código de pareamento de 4 dígitos. Sem bond, [G7AuthOp.NeedsOfficialPairing].
 */
sealed class G7AuthOp {
    /** Escreve um pedaço (≤ 20 bytes) na ExtraData, sem resposta. */
    data class WriteExtraData(val chunk: ByteArray) : G7AuthOp()
    /** Escreve um comando na Authentication (com resposta). */
    data class WriteAuth(val command: ByteArray) : G7AuthOp()
    /** O sensor pediu bond agora (o Android mostra/aceita o pareamento). */
    object CreateBond : G7AuthOp()
    /** Autenticado: pode pedir glicemia. [sharedKey] = chave para guardar e pular o JPAKE da próxima vez. */
    data class Authenticated(val sharedKey: ByteArray?) : G7AuthOp()
    /** O sensor não está pareado com este celular e o primeiro pareamento exige o app oficial. */
    object NeedsOfficialPairing : G7AuthOp()
    /** Código de pareamento errado (o hash do desafio não bateu): limpar a chave salva e parar. */
    object WrongCode : G7AuthOp()
    /** Falhou de um jeito que pede recomeçar a conexão. */
    data class Restart(val reason: String) : G7AuthOp()
}

class DexcomG7Auth(private val plugin: IPluginDA) {

    companion object {
        const val CHUNK = 20

        /** Código de pareamento do G7: 4 dígitos (impresso no aplicador). */
        fun isValidPairingCode(code: String): Boolean = code.length == 4 && code.all { it.isDigit() }
    }

    /** Chave salva de uma conexão anterior com o mesmo sensor (pula as 3 rodadas do JPAKE). */
    fun loadSavedKey(key: ByteArray?) {
        if (key != null && key.size == 16) plugin.setPersistence(2, key)
    }

    /** Chamado quando ExtraData e Authentication estão com notificação/indicação ligadas. */
    fun onReady(): List<G7AuthOp> {
        plugin.amConnected()
        return next()
    }

    /** Indicação recebida na Authentication. */
    fun onAuthentication(bytes: ByteArray): List<G7AuthOp> {
        val ops = mutableListOf<G7AuthOp>()
        try {
            if (plugin.bondNow(bytes)) ops += G7AuthOp.CreateBond
            if (plugin.receivedResponse(bytes)) ops += next()
        } catch (e: java.security.InvalidParameterException) {
            // "Missing QR code"/"Invalid QR code": o caminho de certificados do app oficial.
            ops += G7AuthOp.NeedsOfficialPairing
        } catch (e: SecurityException) {
            ops += G7AuthOp.WrongCode
        } catch (e: Exception) {
            ops += G7AuthOp.Restart(e.message ?: e.javaClass.simpleName)
        }
        return ops
    }

    /** Notificação recebida na ExtraData (pacotes das rodadas do JPAKE). */
    fun onExtraData(bytes: ByteArray): List<G7AuthOp> = try {
        if (plugin.receivedData(bytes)) next() else emptyList()
    } catch (e: Exception) {
        listOf(G7AuthOp.Restart(e.message ?: e.javaClass.simpleName))
    }

    /** `doNext` do xDrip: pede ao plugin o próximo passo e traduz. */
    private fun next(): List<G7AuthOp> {
        val p = plugin.aNext() ?: return emptyList()
        val cmd = p.getOrNull(0)
        return when (p.size) {
            2 -> buildList {
                p[1]?.let { data ->
                    var i = 0
                    while (i < data.size) {
                        add(G7AuthOp.WriteExtraData(data.copyOfRange(i, minOf(i + CHUNK, data.size))))
                        i += CHUNK
                    }
                }
                cmd?.let { add(G7AuthOp.WriteAuth(it)) }
            }
            1 -> if (cmd != null) listOf(G7AuthOp.Authenticated(plugin.getPersistence(1)?.takeIf { it.size == 16 })) else emptyList()
            // Falha de bond com o sensor ([BondFailure] na libkeks): sem o app oficial não há como seguir.
            3 -> listOf(G7AuthOp.NeedsOfficialPairing)
            else -> emptyList()
        }
    }
}
