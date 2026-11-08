package com.gluview.wear.contract

/**
 * Caminhos do Data Layer entre o GluView do celular e o do relógio (seção 3.2 de
 * Projeto DM1/GluView/05_sprint_wearos.md). Mudar qualquer valor aqui quebra a conversa com
 * relógios que já têm a versão anterior instalada — só acrescentar, nunca renomear.
 */
object WearPaths {
    /** celular → relógio: [WatchFaceSnapshot] da leitura mais recente (DataClient, urgente). */
    const val READING = "/gluview/reading"

    /** celular → relógio: design do construtor de visor (Fase 9, mostrador próprio — opcional). */
    const val DESIGN = "/gluview/design"

    /** celular → relógio: token de acesso ao GluSync para o relógio buscar sozinho (Fase 7). */
    const val TOKEN = "/gluview/token"

    /** relógio → celular: resultado da aplicação de um design. */
    const val ACK = "/gluview/ack"

    /** Capability que o app do relógio anuncia; o celular usa para saber se ele está instalado. */
    const val CAPABILITY = "gluview_wear"

    /** Nome do Asset com o bitmap do minigráfico, desenhado NO CELULAR e anexado ao [READING]. */
    const val ASSET_GRAPH = "graph"
}
