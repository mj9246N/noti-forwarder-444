package com.example.notiforwarder444

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object Sender {

    private lateinit var context: Context
    private val WORKER_URL = BuildConfig.WORKER_URL
    private val SECRET_TOKEN = BuildConfig.SECRET_TOKEN
    private val queueFileName = "pending_notifications.json"
    private val mutex = Mutex()

    fun init(appContext: Context) {
        context = appContext.applicationContext
    }

    suspend fun send(app: String, pkg: String, title: String, text: String, time: Long) {
        if (WORKER_URL.isBlank() || SECRET_TOKEN.isBlank()) return

        val payload = JSONObject().apply {
            put("app", app)
            put("package", pkg)
            put("title", title)
            put("text", text)
            put("time", time.toString())
        }

        val success = withContext(Dispatchers.IO) {
            try {
                val json = payload.toString()
                val url = URL(WORKER_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-API-Key", SECRET_TOKEN)
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(json) }
                conn.responseCode
                conn.disconnect()
                true
            } catch (_: Exception) {
                false
            }
        }

        if (!success) {
            saveToQueue(payload)
        } else {
            drainQueue()
        }
    }

    private suspend fun saveToQueue(payload: JSONObject) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(context.filesDir, queueFileName)
                    file.appendText(payload.toString() + "\n")
                } catch (_: Exception) {}
            }
        }
    }

    suspend fun drainQueue() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(context.filesDir, queueFileName)
                    if (!file.exists()) return@withContext

                    val lines = file.readLines().toMutableList()
                    val iterator = lines.iterator()
                    var changed = false

                    while (iterator.hasNext()) {
                        val line = iterator.next()
                        try {
                            val jsonPayload = JSONObject(line)
                            val json = jsonPayload.toString()
                            val url = URL(WORKER_URL)
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.setRequestProperty("X-API-Key", SECRET_TOKEN)
                            conn.doOutput = true
                            OutputStreamWriter(conn.outputStream).use { it.write(json) }
                            val code = conn.responseCode
                            conn.disconnect()
                            if (code == HttpURLConnection.HTTP_OK) {
                                iterator.remove()
                                changed = true
                            } else {
                                break
                            }
                        } catch (_: Exception) {
                            break
                        }
                    }

                    if (changed) {
                        if (lines.isEmpty()) {
                            file.delete()
                        } else {
                            file.writeText(lines.joinToString("\n") + "\n")
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }
}
