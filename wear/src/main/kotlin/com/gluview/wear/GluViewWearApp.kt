package com.gluview.wear

import android.app.Application
import android.util.Log
import com.gluview.wear.data.CloudFetchWorker
import com.gluview.wear.watchface.FacePusher

class GluViewWearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Plano B + reavaliação periódica de "dado velho" (ver CloudFetchWorker).
        CloudFetchWorker.schedule(this)
        // A capability gluview_watchface_push vem de res/values-v36/wear.xml (só Wear OS 6+).
        Log.i("GluViewWear", "Watch Face Push suportado=${FacePusher.isSupported()} (SDK ${android.os.Build.VERSION.SDK_INT})")
    }
}
