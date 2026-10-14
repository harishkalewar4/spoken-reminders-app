package com.example.ttsreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.util.Calendar

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRIGGER_REMINDER = "com.example.ttsreminder.ACTION_TRIGGER_REMINDER"
        const val ACTION_TRIGGER_TIMER = "com.example.ttsreminder.ACTION_TRIGGER_TIMER"
        const val EXTRA_ID = "extra_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_SPOKEN_TEXT = "extra_spoken_text"
        const val EXTRA_RECURRING = "extra_recurring"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                val now = System.currentTimeMillis()
                
                // Process reminders: catch up missed ones or reschedule future/recurring ones
                val reminders = ReminderRepository.getReminders(context)
                val updatedReminders = mutableListOf<Reminder>()
                val missedAnnouncements = mutableListOf<String>()

                reminders.forEach { reminder ->
                    if (reminder.triggerTimeMillis <= now) {
                        // Past due / missed during boot
                        if (reminder.recurring) {
                            val missedText = "Missed reminder: ${reminder.category}, ${reminder.title}."
                            missedAnnouncements.add(missedText)
                            // Advance to next occurrence until future
                            var nextTime = reminder.triggerTimeMillis
                            while (nextTime <= now) {
                                nextTime = ReminderScheduler.computeNextOccurrenceAfter(nextTime)
                            }
                            val updated = reminder.copy(triggerTimeMillis = nextTime)
                            updatedReminders.add(updated)
                            ReminderScheduler.schedule(context, updated)
                        } else {
                            val missedText = "Missed reminder: ${reminder.category}, ${reminder.title}."
                            missedAnnouncements.add(missedText)
                            // Non-recurring missed reminder is dropped (not added back to updatedReminders)
                        }
                    } else {
                        updatedReminders.add(reminder)
                        ReminderScheduler.schedule(context, reminder)
                    }
                }
                ReminderRepository.saveAll(context, updatedReminders)

                // Process timers: clear missed timers
                val timers = TimerRepository.getTimers(context)
                val activeTimers = mutableListOf<TimerItem>()
                val missedTimers = mutableListOf<String>()
                timers.forEach { timer ->
                    if (timer.endTimeMillis <= now) {
                        val labelText = if (timer.label.isNotEmpty()) timer.label else "timer"
                        missedTimers.add("Your timer finished while the phone was off: $labelText.")
                    } else {
                        activeTimers.add(timer)
                        TimerScheduler.schedule(context, timer)
                    }
                }
                TimerRepository.saveAll(context, activeTimers)

                // Start service if there are missed announcements
                if (missedAnnouncements.isNotEmpty() || missedTimers.isNotEmpty()) {
                    val combinedText = (missedAnnouncements + missedTimers).joinToString(" ")
                    val serviceIntent = Intent(context, TtsAlertService::class.java).apply {
                        putExtra(EXTRA_TITLE, "Missed Reminders & Timers")
                        putExtra(EXTRA_SPOKEN_TEXT, combinedText)
                    }
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            }

            ACTION_TRIGGER_REMINDER -> {
                val id = intent.getIntExtra(EXTRA_ID, -1)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "You have a scheduled reminder."
                val category = intent.getStringExtra(EXTRA_CATEGORY) ?: "General"
                val recurring = intent.getBooleanExtra(EXTRA_RECURRING, false)

                if (id != -1) {
                    if (recurring) {
                        val reminders = ReminderRepository.getReminders(context)
                        val target = reminders.find { it.id == id }
                        if (target != null) {
                            val nextTime = ReminderScheduler.computeNextOccurrenceAfter(target.triggerTimeMillis)
                            val updated = target.copy(triggerTimeMillis = nextTime)
                            ReminderRepository.updateReminder(context, updated)
                        }
                    } else {
                        // Mark for removal after speech completes; handled via TtsAlertService
                    }
                }

                val spokenText = "$category reminder: $title. Again, $category reminder: $title."
                val serviceIntent = Intent(context, TtsAlertService::class.java).apply {
                    putExtra(EXTRA_TITLE, "$category: $title")
                    putExtra(EXTRA_SPOKEN_TEXT, spokenText)
                    if (!recurring && id != -1) {
                        putExtra(EXTRA_ID, id)
                        putExtra("extra_type", "reminder")
                    }
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
