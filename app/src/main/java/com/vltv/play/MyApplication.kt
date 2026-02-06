package com.vltv.play

import android.app.Application
import android.content.Context

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Receiver registrado UMA VEZ quando app inicia
        DownloadHelper.registerReceiver(this)
    }
}
