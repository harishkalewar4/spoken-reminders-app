package com.example.ttsreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ReminderReceiver : BroadcastReceiver() {

companion object {
    const val ACTION_TRIGGER_REMINDER = "com.example.ttsreminder.ACTION_TRIGGER_REMINDER"
    const val ACTION_TRIGGER_TIMER = "com.example.ttsreminder.ACTION_TRIGGER_TIMER"
    const val EXTRA_ID = "extra_id"
    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_CATEGORY = "extra_category"
    const val EXTRA_LABEL = "extra_label"
    const val EXTRA_SPOKEN_TEXT = "extra_spoken_text"
}

override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
        Intent.ACTION_BOOT_COMPLETED -> {
            val now = System.currentTimeMillis()
            ReminderRepository.getReminders(context).forEach { reminder ->
                if (reminder.triggerTimeMillis > now) {
                    ReminderScheduler.schedule(context, reminder)
                }
            }
            TimerRepository.getTimers(context).forEach { timer ->
                if (timer.endTimeMillis > now) {
                    TimerScheduler.schedule(context, timer)
                }
            }
        }

        ACTION_TRIGGER_REMINDER -> {
            val id = intent.getIntExtra(EXTRA_ID, -1)
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "You have a scheduled reminder."
            val category = intent.getStringExtra(EXTRA_CATEGORY) ?: "General"

            if (id != -1) {
                ReminderRepository.deleteReminder(context, id)
            }

            val spokenText = "$category reminder: $title. Again, $category reminder: $title."
            val serviceIntent = Intent(context, TtsAlertService::class.java).apply {
                putExtra(EXTRA_TITLE, "$category: $title")
                putExtra(EXTRA_SPOKEN_TEXT, spokenText)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        ACTION_TRIGGER_TIMER -> {
            val id = intent.getIntExtra(EXTRA_ID, -1)
            val label = intent.getStringExtra(EXTRA_LABEL)?.trim().orEmpty()

            if (id != -1) {
                TimerRepository.deleteTimer(context, id)
            }

            val spokenText = if (label.isNotEmpty()) {
                "Timer finished: $label. Again, timer finished: $label."
            } else {
                "Your timer is finished. Again, your timer is finished."
            }

            val notifText = if (label.isNotEmpty()) "Timer finished: $label" else "Your timer is finished"
            val serviceIntent = Intent(context, TtsAlertService::class.java).apply {
                putExtra(EXTRA_TITLE, notifText)
                putExtra(EXTRA_SPOKEN_TEXT, spokenText)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}


}
