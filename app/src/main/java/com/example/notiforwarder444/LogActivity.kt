package com.example.notiforwarder444

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LogActivity : AppCompatActivity() {

    private lateinit var logText: TextView
    private lateinit var btnCopy: Button
    private lateinit var btnClear: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        logText = findViewById(R.id.log_text)
        btnCopy = findViewById(R.id.btn_copy_logs)
        btnClear = findViewById(R.id.btn_clear_logs)

        btnCopy.setOnClickListener {
            val text = logText.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("logs", text))
            Toast.makeText(this, "لاگ‌ها کپی شد", Toast.LENGTH_SHORT).show()
        }

        btnClear.setOnClickListener {
            LogManager.clear()
            updateLogDisplay()
        }

        LogManager.addListener { runOnUiThread { updateLogDisplay() } }
        updateLogDisplay()
    }

    private fun updateLogDisplay() {
        logText.text = LogManager.getLogs().joinToString("\n")
    }
}
