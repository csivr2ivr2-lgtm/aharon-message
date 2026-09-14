package com.aharon.message

import android.content.Context
import com.aharon.message.crypto.IdentityManager
import com.aharon.message.data.MessageStore
import com.aharon.message.engine.MessagingEngine

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val settings = AppSettings(appContext)
    val store = MessageStore(appContext)
    val identity = IdentityManager(appContext)
    val engine = MessagingEngine(appContext, store, identity, settings)
}
