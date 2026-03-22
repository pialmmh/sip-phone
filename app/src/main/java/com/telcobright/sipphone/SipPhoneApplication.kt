package com.telcobright.sipphone

import android.app.Application

class SipPhoneApplication : Application() {

    companion object {
        init {
            System.loadLibrary("sipphone-native")
        }
    }

    override fun onCreate() {
        super.onCreate()
    }
}
