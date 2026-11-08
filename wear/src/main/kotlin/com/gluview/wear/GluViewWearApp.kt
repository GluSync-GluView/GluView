package com.gluview.wear

import android.app.Application
import com.gluview.wear.data.CloudFetchWorker

class GluViewWearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Plano B + reavaliação periódica de "dado velho" (ver CloudFetchWorker).
        CloudFetchWorker.schedule(this)
    }
}
