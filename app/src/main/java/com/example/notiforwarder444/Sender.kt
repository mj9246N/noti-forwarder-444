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
        LogManager.add("Sender.init, URL=$WORKER_URL")
    }

    suspend fun send(app: String, pkg: String, title: String, text: String, time: Long, battery: Int) {
        if (WORKER_URL.isBlank() || SECRET_TOKEN.isBlank()) {
            LogManager.add("Send aborted: empty URL or token")
            return
        }

        val payload = JSONObject().apply {
            put("app", app)
            put("package", pkg)
            put("title", title)
            put("text", text)
            put("time", time.toString())
            put("battery", battery)
        }

        val success = withContext(Dispatchers.IO) {
            try {
                val json = payload.toString()
                LogManager.add("POST to $WORKER_URL")
                val url = URL(WORKER_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-API-Key", SECRET_TOKEN)
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(json) }
                val code = conn.responseCode
                conn.disconnect()
                LogManager.add("Response code: $code")
                code == HttpURLConnection.HTTP_OK
            } catch (e: Exception) {
                LogManager.add("Network error: ${e.message}")
                false
            }
        }

        if (!success) {
            LogManager.add("Will save to queue")
            saveToQueue(payload)
        } else {
            LogManager.add("Success, draining queue")
            drainQueue()
        }
    }

    private suspend fun saveToQueue(payload: JSONObject) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(context.filesDir, queueFileName)
                    file.appendText(payload.toString() + "\n")
                    LogManager.add("Queue size: ${file.length()} bytes")
                } catch (e: Exception) {
                    LogManager.add("Queue save error: ${e.message}")
                }
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
                                LogManager.add("Drained one queued notification")
                            } else {
                                LogManager.add("Drain stopped, server response $code")
                                break
                            }
                        } catch (e: Exception) {
                            LogManager.add("Drain stopped, error: ${e.message}")
                            break
                        }
                    }

                    if (changed) {
                        if (lines.isEmpty()) {
                            file.delete()
                            LogManager.add("Queue empty, file deleted")
                        } else {
                            file.writeText(lines.joinToString("\n") + "\n")
                        }
                    }
                } catch (e: Exception) {
                    LogManager.add("DrainQueue unexpected: ${e.message}")
                }
            }
        }
    }
}
