package com.example.notiforwarder444

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*

class NotiAccessibilityService : AccessibilityService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Sender.init(this)
        LogManager.add("AccessibilityService connected")
        scope.launch {
            Sender.drainQueue()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
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

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        val battery = getBatteryLevel()
        LogManager.add("Sending: $appName, batt=$battery%")

        scope.launch {
            Sender.send(appName, packageName, title, text, time, battery)
        }
    }

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

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        LogManager.add("AccessibilityService destroyed")
    }
}
