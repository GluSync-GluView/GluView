package com.gluview.app.data.remote

import com.gluview.app.util.RxErrorHandlers
import com.gluview.app.util.RxUndeliverableClassifier
import com.microsoft.signalr.HubConnectionBuilder
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * L29 com o cliente SignalR de verdade: reproduz o crash do S23 FE (30/09 e 11/10/2026).
 *
 * O GluSyncUploader desiste do start() depois de 20 s (servidor lento, ex.: Render acordando) e
 * chama stop() com a negociação HTTP ainda em curso. Quando a negociação enfim falha, a limpeza
 * do stop() chama transport.stop() num transporte que nunca foi criado: NullPointerException
 * numa thread do OkHttp, entregue ao RxJavaPlugins do RxJava 3 — sem tratador, o processo cai.
 *
 * Com o tratador de produção instalado, nada pode sair como CRASH: no 8.0.x o NPE chegava e era
 * reconhecido como falha de conexão (IGNORE); no 10.0, que checa o transporte nulo, não chega erro.
 * E o start() continua devolvendo a falha a quem esperava, então o uploader tenta na próxima leitura.
 */
class SignalRStopWithoutTransportTest {

    @After fun resetRx() = RxJavaPlugins.reset()

    @Test fun `stop durante negociacao que falha nao derruba o app`() {
        // O tratador de PRODUÇÃO (o mesmo que GluViewApplication instala), com um destino que anota.
        val handled = LinkedBlockingQueue<Pair<RxUndeliverableClassifier.Action, Throwable?>>()
        RxJavaPlugins.setErrorHandler(RxErrorHandlers.rx3 { action, _, cause -> handled.add(action to cause) })

        ServerSocket(0).use { server ->
            val accepted = CountDownLatch(1)
            val release = CountDownLatch(1)
            // Servidor que aceita a negociação e fecha sem responder (servidor fora do ar no meio).
            thread(isDaemon = true) {
                server.accept().use { accepted.countDown(); release.await(10, TimeUnit.SECONDS) }
            }

            val conn = HubConnectionBuilder.create("http://127.0.0.1:${server.localPort}/ws/live").build()
            val startTask = conn.start()
            assertTrue("negociação não chegou ao servidor", accepted.await(10, TimeUnit.SECONDS))

            conn.stop()          // o que o uploader faz ao estourar os 20 s
            release.countDown()  // agora a negociação falha (conexão fechada sem resposta)

            // O start devolve a falha a quem esperava (o uploader captura e tenta na próxima leitura).
            val startError = runCatching { startTask.blockingAwait(10, TimeUnit.SECONDS) }.exceptionOrNull()
            assertNotNull("o start deveria falhar", startError)

            // Nada que derrube o processo: no SignalR 8.0.x o NPE do stop chegava ao tratador do
            // RxJava 3 e sai como IGNORE; no 10.0 (transport != null) não chega erro nenhum.
            val seen = generateSequence { handled.poll(3, TimeUnit.SECONDS) }.toList()
            assertTrue("erro que derrubaria o app: $seen", seen.none { it.first == RxUndeliverableClassifier.Action.CRASH })
            seen.forEach { (_, cause) ->
                assertTrue("erro inesperado do SignalR: $cause", RxUndeliverableClassifier.isSignalRStopWithoutTransport(cause))
            }
        }
    }
}
