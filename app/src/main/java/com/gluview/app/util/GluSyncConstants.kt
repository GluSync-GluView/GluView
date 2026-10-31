package com.gluview.app.util

import com.gluview.app.BuildConfig

/** URL do GluSync — mesma para todos os usuários de um build. Definida na compilação
 *  (propriedade Gradle `gluview.serverUrl`, padrão = servidor oficial; ver app/build.gradle.kts). */
object GluSyncConstants {
    const val BASE_URL = BuildConfig.GLUSYNC_BASE_URL
}
