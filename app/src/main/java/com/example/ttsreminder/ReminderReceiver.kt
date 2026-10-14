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
        const val EXTRA_RECURRING = "extra_recurring"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                try {
                    val now = System.currentTimeMillis()
                    val reminders = ReminderRepository.getReminders(context)
                    val updatedReminders = mutableListOf<Reminder>()
                    val missedAnnouncements = mutableListOf<String>()

                    reminders.forEach { reminder ->
                        if (reminder.triggerTimeMillis <= now) {
                            if (reminder.recurring) {
                                missedAnnouncements.add("Missed reminder: ${reminder.category}, ${reminder.title}.")
                                var nextTime = reminder.triggerTimeMillis
                                while (nextTime <= now) {
                                    nextTime = ReminderScheduler.computeNextOccurrenceAfter(nextTime)
                                }
                                val updated = reminder.copy(triggerTimeMillis = nextTime)
                                updatedReminders.add(updated)
                                ReminderScheduler.schedule(context, updated)
                            } else {
                                missedAnnouncements.add("Missed reminder: ${reminder.category}, ${reminder.title}.")
                            }
                        } else {
                            updatedReminders.add(reminder)
                            ReminderScheduler.schedule(context, reminder)
                        }
                    }
                    ReminderRepository.saveAll(context, updatedReminders)

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

                    if (missedAnnouncements.isNotEmpty() || missedTimers.isNotEmpty()) {
                        val combinedText = (missedAnnouncements + missedTimers).joinToString(" ")
                        val serviceIntent = Intent(context, TtsAlertService::class.java).apply {
                            putExtra(EXTRA_TITLE, "Missed Reminders & Timers")
                            putExtra(EXTRA_CATEGORY, "General")
                            putExtra(EXTRA_SPOKEN_TEXT, combinedText)
                        }
                        ContextCompat.startForegroundService(context, serviceIntent)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            ACTION_TRIGGER_REMINDER -> {
                val id = intent.getIntExtra(EXTRA_ID, -1)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "You have a scheduled reminder."
                val category = intent.getStringExtra(EXTRA_CATEGORY) ?: "General"
                val recurring = intent.getBooleanExtra(EXTRA_RECURRING, false)

                ReminderRepository.recordAlarmEvent(context, "alarm received ($title)")

                // Start the foreground service FIRST (exact v1.0.1 behavior) so nothing can delay or block it
                val spokenText = "$category reminder: $title. Again, $category reminder: $title."
                val serviceIntent = Intent(context, TtsAlertService::class.java).apply {
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_CATEGORY, category)
                    putExtra(EXTRA_SPOKEN_TEXT, spokenText)
                    if (!recurring && id != -1) {
                        putExtra(EXTRA_ID, id)
                    }
                }
                ContextCompat.startForegroundService(context, serviceIntent)

                // Handle recurring reschedule or non-recurring cleanup safely after starting service
                if (id != -1) {
                    try {
                        if (recurring) {
                            val reminders = ReminderRepository.getReminders(context)
                            val target = reminders.find { it.id == id }
                            if (target != null) {
                                val now = System.currentTimeMillis()
                                var nextTime = ReminderScheduler.computeNextOccurrenceAfter(target.triggerTimeMillis)
                                while (nextTime <= now) {
                                    nextTime = ReminderScheduler.computeNextOccurrenceAfter(nextTime)
                                }
                                val updated = target.copy(triggerTimeMillis = nextTime)
                                ReminderRepository.updateReminder(context, updated)
                            }
                        } else {
                            // Remove from stored list without calling alarmManager.cancel on the currently firing alarm
                            ReminderRepository.removeReminderRecordOnly(context, id)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            ACTION_TRIGGER_TIMER -> {
                val id = intent.getIntExtra(EXTRA_ID, -1)
                val label = intent.getStringExtra(EXTRA_LABEL)?.trim().orEmpty()

                ReminderRepository.recordAlarmEvent(
                    context,
                    "alarm received (Timer ${label.ifEmpty { "default" }})"
                )

                val spokenText = if (label.isNotEmpty()) {
                    "Timer finished: $label. Again, timer finished: $label."
                } else {
                    "Your timer is finished. Again, your timer is finished."
                }
                val notifTitle = if (label.isNotEmpty()) label else "Timer Finished"

                val serviceIntent = Intent(context, TtsAlertService::class.java).apply {
                    putExtra(EXTRA_TITLE, notifTitle)
                    putExtra(EXTRA_CATEGORY, "Timer")
                    putExtra(EXTRA_SPOKEN_TEXT, spokenText)
                }
                ContextCompat.startForegroundService(context, serviceIntent)

                if (id != -1) {
                    try {
                        TimerRepository.removeTimerRecordOnly(context, id)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
}
