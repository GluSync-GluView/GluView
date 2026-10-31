package com.gluview.app.watchface

/**
 * Migração de schema do projeto de watchface (ver plano 06_watchfaces_v2_arquitetura.md, §6.3).
 * Cada migração é uma função pura `design -> design`, encadeada por versão. Nunca reescreve o
 * projeto de origem silenciosamente — só roda em memória ao carregar; persistir a versão migrada
 * é responsabilidade explícita de quem salva (ver WatchFaceEntityMappers.toDesign()).
 */
object WatchFaceMigrations {
    const val CURRENT_SCHEMA_VERSION = 2

    /** Migra `design` até [CURRENT_SCHEMA_VERSION]. Idempotente: já migrado, devolve como está. */
    fun migrate(design: WatchFaceDesign): WatchFaceDesign {
        var current = design
        if (current.schemaVersion < 2) current = migrateV1toV2(current)
        return current
    }

    /**
     * v1 -> v2: preenche xFrac/yFrac/widthFrac/heightFrac em cada componente a partir da posição
     * ABSOLUTA atual (x/y/width/height) e do wearable do próprio design — os campos absolutos não
     * mudam. Componente que já tem fração preenchida (ex.: criado depois desta migração existir)
     * é preservado sem recálculo.
     */
    private fun migrateV1toV2(design: WatchFaceDesign): WatchFaceDesign {
        val migratedComponents = design.components.map { component ->
            if (component.xFrac != null) component else component.withFractional(design.wearable)
        }
        return design.copy(components = migratedComponents, schemaVersion = 2)
    }
}
