package com.example.ttsreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRIGGER_REMINDER = "com.example.ttsreminder.ACTION_TRIGGER_REMINDER"
        const val EXTRA_ID = "extra_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CATEGORY = "extra_category"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // Re-schedule all upcoming reminders after device reboot
                val now = System.currentTimeMillis()
                ReminderRepository.getReminders(context).forEach { reminder ->
                    if (reminder.triggerTimeMillis > now) {
                        ReminderScheduler.schedule(context, reminder)
                    }
                }
            }

            ACTION_TRIGGER_REMINDER -> {
                val id = intent.getIntExtra(EXTRA_ID, -1)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "You have a scheduled reminder."
                val category = intent.getStringExtra(EXTRA_CATEGORY) ?: "General"

                // Remove the fired one-time reminder from active storage
                if (id != -1) {
                    ReminderRepository.deleteReminder(context, id)
                }

                // Start the Foreground Service that speaks via Android's default TTS engine
                val serviceIntent = Intent(context, TtsAlertService::class.java).apply {
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_CATEGORY, category)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
