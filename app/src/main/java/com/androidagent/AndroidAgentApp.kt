package com.androidagent

import android.app.Application
import com.androidagent.data.AppPreferences
import com.androidagent.data.db.AppDatabase

class AndroidAgentApp : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppPreferences.init(this)
        database = AppDatabase.getInstance(this)
    }

    companion object {
        lateinit var instance: AndroidAgentApp
            private set
    }
}
