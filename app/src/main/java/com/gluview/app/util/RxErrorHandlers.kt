package com.gluview.app.util

import io.reactivex.exceptions.UndeliverableException
import io.reactivex.rxjava3.exceptions.UndeliverableException as Rx3UndeliverableException

/**
 * Tratadores globais de erro do RxJava 2 e 3 (instalados por GluViewApplication). Separados aqui
 * para o teste instalar EXATAMENTE o tratador de produção — sem isto, trocar classifyRx3 por
 * classify ou esquecer o do RxJava 3 deixaria todos os testes verdes e o crash da L29 voltaria.
 */
object RxErrorHandlers {

    /** O que fazer com o erro já classificado (log, ignorar ou repassar ao crash). */
    fun interface Sink {
        fun handle(action: RxUndeliverableClassifier.Action, error: Throwable, cause: Throwable?)
    }

    fun rx2(sink: Sink) = io.reactivex.functions.Consumer<Throwable> { e ->
        val cause = if (e is UndeliverableException) e.cause else e
        sink.handle(RxUndeliverableClassifier.classify(cause), e, cause)
    }

    /** RxJava 3 = cliente SignalR (envio ao GluSync, seguidor ao vivo). Ver classifyRx3 (L29). */
    fun rx3(sink: Sink) = io.reactivex.rxjava3.functions.Consumer<Throwable> { e ->
        val cause = if (e is Rx3UndeliverableException) e.cause else e
        sink.handle(RxUndeliverableClassifier.classifyRx3(cause), e, cause)
    }
}
