package com.familink.emulator

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object NativeFirebase {
    private const val API_KEY = "AIzaSyBnS2Uuvyx46hcuPSKWlbW2B0vNsSXeoXU"
    private const val APP_ID = "1:327460473894:web:64c5cb2b2c5936a36e2ae7"
    private const val PROJECT_ID = "loyal-jigsaw-5dtd0"
    private const val DATABASE_ID = "ai-studio-9b47464d-83f5-4195-ab77-803c3474f722"
    fun initialize(context: Context): FirebaseApp {
        FirebaseApp.getApps(context).firstOrNull { it.name == "[DEFAULT]" }?.let { return it }
        val options = FirebaseOptions.Builder().setApiKey(API_KEY).setApplicationId(APP_ID).setProjectId(PROJECT_ID).setStorageBucket("loyal-jigsaw-5dtd0.firebasestorage.app").build()
        return FirebaseApp.initializeApp(context, options)!!
    }
    fun databaseId() = DATABASE_ID
}
