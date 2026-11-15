package com.gluview.mirror

/** Núcleo do espelhamento do GluView (plano 21, F2). Kotlin puro: nada aqui depende de Android. */
object MirrorCore {
    /** Versão do protocolo do espelho; quem recebe recusa versão maior que a sua. */
    const val PROTOCOL_VERSION = 1
}
