package com.example.notiforwarder444

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class NotiAccessibilityService : AccessibilityService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var smsObserver: ContentObserver? = null
    private var lastSentSmsId: Long = -1
    private val smsPollHandler = Handler(Looper.getMainLooper())
    private var smsPollRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Sender.init(this)
        LogManager.add("AccessibilityService connected")

        // بررسی مجوز READ_SMS
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            LogManager.add("READ_SMS permission granted")
        } else {
            LogManager.add("READ_SMS permission DENIED!")
        }

        scope.launch {
            Sender.drainQueue()
        }
        registerSmsObserver()
        startSmsPolling()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // فیلتر incallui
        if (packageName == "com.android.incallui") {
            LogManager.add("Filtered out: $packageName")
            return
        }

        // فیلتر کردن برنامهٔ پیش‌فرض پیامک
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

    // ========== خواندن پیامک‌ها: Observer + Polling ==========
    private fun registerSmsObserver() {
        smsObserver = object : ContentObserver(Handler()) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                LogManager.add("SMS ContentObserver triggered")
                readLatestSms()
            }
        }
        contentResolver.registerContentObserver(
            Telephony.Sms.Inbox.CONTENT_URI,
            true,
            smsObserver!!
        )
        LogManager.add("SMS observer registered")
        // بار اول فقط شناسه آخرین پیامک را ذخیره کن
        readLatestSms(updateLastIdOnly = true)
    }

    private fun startSmsPolling() {
        smsPollRunnable = object : Runnable {
            override fun run() {
                readLatestSms()
                smsPollHandler.postDelayed(this, 30_000) // هر ۳۰ ثانیه
            }
        }
        smsPollHandler.postDelayed(smsPollRunnable!!, 30_000)
        LogManager.add("SMS polling started (every 30s)")
    }

    private fun readLatestSms(updateLastIdOnly: Boolean = false) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            LogManager.add("Cannot read SMS: permission missing")
            return
        }

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
                                LogManager.add("SMS initialized with id=$id")
                                return@withContext
                            }
                            if (id > lastSentSmsId) {
                                lastSentSmsId = id
                                val address = it.getString(1) ?: "ناشناس"
                                val body = it.getString(2) ?: ""
                                val date = it.getLong(3)
                                LogManager.add("New SMS from $address (id=$id)")
                                Sender.send(
                                    "پیامک دریافتی",
                                    "com.android.sms",
                                    address,
                                    body,
                                    date,
                                    getBatteryLevel()
                                )
                            } else {
                                LogManager.add("No new SMS (last id=$lastSentSmsId, db id=$id)")
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
        smsObserver?.let { contentResolver.unregisterContentObserver(it) }
        smsPollRunnable?.let { smsPollHandler.removeCallbacks(it) }
        job.cancel()
        LogManager.add("AccessibilityService destroyed")
    }
}
