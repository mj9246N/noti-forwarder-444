package com.example.notiforwarder444

import android.os.Handler
import android.os.Looper
import java.util.*

object LogManager {
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val listeners = mutableListOf<() -> Unit>()

    fun add(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add("[$timestamp] $message")
        if (logs.size > 500) logs.removeAt(0)
        for (l in listeners) l.invoke()
    }

    fun getLogs(): List<String> = logs.toList()

    fun clear() = logs.clear()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
