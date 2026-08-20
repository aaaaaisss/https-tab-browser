package com.example.httpsbrowser

import android.app.Application

class BrowserApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
    }
}
