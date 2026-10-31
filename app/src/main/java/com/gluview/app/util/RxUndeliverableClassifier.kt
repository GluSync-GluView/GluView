package com.gluview.app.util

import com.polidea.rxandroidble2.exceptions.BleException
import java.io.IOException
import java.net.SocketException

/**
 * O que fazer com a causa de uma UndeliverableException — um erro que chegou depois que a cadeia
 * RxJava já foi descartada, então não há mais assinante pra receber. Extraído de
 * GluViewApplication (RxJavaPlugins.setErrorHandler) pra ser testável sem Robolectric.
 *
 * Categorias seguem o exemplo da própria doc do RxJava2:
 * https://github.com/ReactiveX/RxJava/wiki/What's-different-in-2.0#error-handling
 */
object RxUndeliverableClassifier {
    enum class Action { IGNORE, CRASH, LOG_UNKNOWN }

    fun classify(cause: Throwable?): Action = when (cause) {
        // BLE desconectou (ou GATT deu timeout/erro) bem na hora do dispose — o caso do crash
        // do Mi Band (MiBandUploader.connectAndUpload, disposables.clear() no finally).
        is BleException,
        // Rede caiu ou uma chamada bloqueante foi interrompida pelo dispose — mesma categoria,
        // sem lugar pra ir.
        is IOException,
        is SocketException,
        is InterruptedException -> Action.IGNORE

        // Bug de verdade (nosso ou de um operador Rx) — não esconder, deixa crashar.
        is NullPointerException,
        is IllegalArgumentException,
        is IllegalStateException -> Action.CRASH

        else -> Action.LOG_UNKNOWN
    }

    /**
     * Erros que chegam ao RxJavaPlugins do RxJava 3 — hoje só o cliente SignalR (envio ao GluSync
     * e seguidor ao vivo) usa RxJava 3. Mesmas regras de [classify], com UMA exceção estreita:
     * [isSignalRStopWithoutTransport] é falha de conexão, não bug nosso, e não pode derrubar o
     * processo (que leva junto a leitura do sensor e os alertas).
     */
    fun classifyRx3(cause: Throwable?): Action =
        if (isSignalRStopWithoutTransport(cause)) Action.IGNORE else classify(cause)

    /**
     * Defeito do cliente SignalR 8.0.x (corrigido no 10.0: `transport != null ? transport.stop()`;
     * o app usa o 10.0 desde 17/10/2026 e a regra fica como defesa, caso o defeito volte):
     * um stop() chamado enquanto o start() ainda negocia — o GluSyncUploader faz isso quando o
     * servidor demora mais de 20 s, ex.: Render acordando — se inscreve no fim do start. Se a
     * negociação HTTP falha, o transporte nunca foi criado e a limpeza chama transport.stop() em
     * nulo: NullPointerException dentro de HubConnection.lambda$stop$N, numa thread do OkHttp.
     * Inofensivo (não havia o que parar; o start já devolveu o erro a quem esperava). Crash visto
     * no S23 FE em 30/09 e 11/10/2026 (L29).
     */
    fun isSignalRStopWithoutTransport(cause: Throwable?): Boolean {
        if (cause !is NullPointerException) return false
        val top = cause.stackTrace.firstOrNull() ?: return false
        return top.className == SIGNALR_HUB_CONNECTION && top.methodName.contains("stop")
    }

    private const val SIGNALR_HUB_CONNECTION = "com.microsoft.signalr.HubConnection"
}
