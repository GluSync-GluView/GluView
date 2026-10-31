package com.gluview.app.di

import android.content.Context
import androidx.room.Room
import com.gluview.app.data.local.AppDatabase
import com.gluview.app.data.local.dao.AlertLogDao
import com.gluview.app.data.local.dao.AppLogDao
import com.gluview.app.data.local.dao.EventLogDao
import com.gluview.app.data.local.dao.FingerstickLogDao
import com.gluview.app.data.local.dao.GlucoseDao
import com.gluview.app.data.local.dao.SensorHistoryDao
import com.gluview.app.data.local.dao.WatchFaceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "gluview.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13
            )
            .build()

    @Provides fun provideGlucoseDao(db: AppDatabase):      GlucoseDao      = db.glucoseDao()
    @Provides fun provideAlertLogDao(db: AppDatabase):     AlertLogDao      = db.alertLogDao()
    @Provides fun provideEventLogDao(db: AppDatabase):     EventLogDao      = db.eventLogDao()
    @Provides fun provideFingerstickLogDao(db: AppDatabase): FingerstickLogDao = db.fingerstickLogDao()
    @Provides fun provideAppLogDao(db: AppDatabase):       AppLogDao        = db.appLogDao()
    @Provides fun provideWatchFaceDao(db: AppDatabase):    WatchFaceDao     = db.watchFaceDao()
    @Provides fun provideSensorHistoryDao(db: AppDatabase): SensorHistoryDao = db.sensorHistoryDao()
}
