package com.gluview.app.di

import javax.inject.Qualifier

/** Qualifica o OkHttpClient/Retrofit/GluSyncApi dedicados à renovação de token — ver
 * [com.gluview.app.data.remote.auth.TokenRefreshCoordinator] para o porquê de serem separados
 * do cliente HTTP principal. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RefreshApi
