package com.gluview.app.util

import com.polidea.rxandroidble2.exceptions.BleDisconnectedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException

/**
 * Caso de origem: crash de 2026-08-13 em MiBandUploader.connectAndUpload — BLE desconecta
 * (GATT_SUCCESS) bem na hora do disposables.clear() do finally, RxJava não tem pra onde mandar o
 * erro. Ver GluViewApplication.installRxJavaErrorHandler.
 */
class RxUndeliverableClassifierTest {

    @Test
    fun `desconexao BLE é ignorada, não crasha`() {
        val cause = BleDisconnectedException("AA:BB:CC:DD:EE:FF", 0)
        assertEquals(RxUndeliverableClassifier.Action.IGNORE, RxUndeliverableClassifier.classify(cause))
    }

    @Test
    fun `erro de rede é ignorado`() {
        assertEquals(RxUndeliverableClassifier.Action.IGNORE, RxUndeliverableClassifier.classify(IOException("connection reset")))
        assertEquals(RxUndeliverableClassifier.Action.IGNORE, RxUndeliverableClassifier.classify(SocketException("closed")))
    }

    @Test
    fun `interrupcao por dispose é ignorada`() {
        assertEquals(RxUndeliverableClassifier.Action.IGNORE, RxUndeliverableClassifier.classify(InterruptedException()))
    }

    @Test
    fun `NPE e estado ilegal continuam crashando — não escondem bug de verdade`() {
        assertEquals(RxUndeliverableClassifier.Action.CRASH, RxUndeliverableClassifier.classify(NullPointerException()))
        assertEquals(RxUndeliverableClassifier.Action.CRASH, RxUndeliverableClassifier.classify(IllegalArgumentException()))
        assertEquals(RxUndeliverableClassifier.Action.CRASH, RxUndeliverableClassifier.classify(IllegalStateException()))
    }

    // L29 — o crash do S23 FE (30/09 e 11/10/2026): NPE no topo de HubConnection.lambda$stop$16.
    private fun npeAt(className: String, method: String) = NullPointerException(
        "Attempt to invoke interface method 'Transport.stop()' on a null object reference"
    ).apply {
        stackTrace = arrayOf(
            StackTraceElement(className, method, "HubConnection.java", 448),
            StackTraceElement("io.reactivex.rxjava3.internal.observers.CallbackCompletableObserver", "onComplete", "x.java", 53)
        )
    }

    @Test
    fun `RxJava 3 - NPE do SignalR ao parar conexao que nunca abriu é ignorado`() {
        val npe = npeAt("com.microsoft.signalr.HubConnection", "lambda\$stop\$16")
        assertTrue(RxUndeliverableClassifier.isSignalRStopWithoutTransport(npe))
        assertEquals(RxUndeliverableClassifier.Action.IGNORE, RxUndeliverableClassifier.classifyRx3(npe))
    }

    @Test
    fun `RxJava 3 - qualquer outro NPE continua crashando`() {
        assertEquals(RxUndeliverableClassifier.Action.CRASH, RxUndeliverableClassifier.classifyRx3(NullPointerException()))
        assertEquals(RxUndeliverableClassifier.Action.CRASH,
            RxUndeliverableClassifier.classifyRx3(npeAt("com.microsoft.signalr.HubConnection", "invoke")))
        assertEquals(RxUndeliverableClassifier.Action.CRASH,
            RxUndeliverableClassifier.classifyRx3(npeAt("com.gluview.app.data.remote.GluSyncUploader", "stop")))
        assertFalse(RxUndeliverableClassifier.isSignalRStopWithoutTransport(IllegalStateException()))
    }

    @Test
    fun `RxJava 3 - segue as mesmas regras do RxJava 2 no resto`() {
        assertEquals(RxUndeliverableClassifier.Action.IGNORE, RxUndeliverableClassifier.classifyRx3(IOException("negotiate")))
        assertEquals(RxUndeliverableClassifier.Action.CRASH, RxUndeliverableClassifier.classifyRx3(IllegalArgumentException()))
        assertEquals(RxUndeliverableClassifier.Action.LOG_UNKNOWN, RxUndeliverableClassifier.classifyRx3(RuntimeException("???")))
    }

    @Test
    fun `RxJava 2 nao muda - NPE (mesmo o do SignalR) continua crashando ali`() {
        assertEquals(RxUndeliverableClassifier.Action.CRASH,
            RxUndeliverableClassifier.classify(npeAt("com.microsoft.signalr.HubConnection", "lambda\$stop\$16")))
    }

    @Test
    fun `excecao desconhecida so loga, não ignora nem crasha`() {
        assertEquals(RxUndeliverableClassifier.Action.LOG_UNKNOWN, RxUndeliverableClassifier.classify(RuntimeException("???")))
        assertEquals(RxUndeliverableClassifier.Action.LOG_UNKNOWN, RxUndeliverableClassifier.classify(null))
    }
}
