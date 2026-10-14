package com.example.ttsreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
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
        const val EXTRA_DELETE_REMINDER_ID = "extra_delete_reminder_id"

        @Volatile
        private var transitionWakeLock: PowerManager.WakeLock? = null

        fun acquireTransitionWakeLock(context: Context) {
            try {
                val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = transitionWakeLock ?: pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SpokenReminders::ReceiverTransitionWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    transitionWakeLock = this
                }
                wl.acquire(15_000L)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun releaseTransitionWakeLock() {
            try {
                if (transitionWakeLock?.isHeld == true) {
                    transitionWakeLock?.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * Processes any past-due reminders or timers (for boot catch-up or app-launch catch-up)
         * and reschedules all active future alarms.
         */
        fun processCatchUpAndReschedule(context: Context) {
            val now = System.currentTimeMillis()

            val reminders = ReminderRepository.getReminders(context)
            val updatedReminders = mutableListOf<Reminder>()
            val missedAnnouncements = mutableListOf<String>()

            reminders.forEach { reminder ->
                if (reminder.triggerTimeMillis <= now) {
                    if (reminder.recurring) {
                        val missedText = "Missed reminder: ${reminder.category}, ${reminder.title}."
                        missedAnnouncements.add(missedText)
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
                acquireTransitionWakeLock(context)
                val combinedText = (missedAnnouncements + missedTimers).joinToString(" ")
                val serviceIntent = Intent(context, TtsAlertService::class.java).apply {
                    putExtra(EXTRA_TITLE, "Missed Reminders & Timers")
                    putExtra(EXTRA_SPOKEN_TEXT, combinedText)
                }
                startAlertServiceSafely(context, serviceIntent)
            }
        }

        private fun startAlertServiceSafely(context: Context, serviceIntent: Intent) {
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                try {
                    context.startService(serviceIntent)
                } catch (ignored: Exception) {
                    ignored.printStackTrace()
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        acquireTransitionWakeLock(context)

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                processCatchUpAndReschedule(context)
            }

            ACTION_TRIGGER_REMINDER -> {
                val id = intent.getIntExtra(EXTRA_ID, -1)
                val stored = if (id != -1) {
                    ReminderRepository.getReminders(context).find { it.id == id }
                } else null

                val title = stored?.title ?: intent.getStringExtra(EXTRA_TITLE) ?: "You have a scheduled reminder."
                val category = stored?.category ?: intent.getStringExtra(EXTRA_CATEGORY) ?: "General"
                val recurring = stored?.recurring ?: intent.getBooleanExtra(EXTRA_RECURRING, false)

                if (id != -1 && recurring) {
                    val now = System.currentTimeMillis()
                    var nextTime = ReminderScheduler.computeNextOccurrenceAfter(
                        stored?.triggerTimeMillis ?: now
                    )
                    while (nextTime <= now) {
                        nextTime = ReminderScheduler.computeNextOccurrenceAfter(nextTime)
                    }
                    if (stored != null) {
                        ReminderRepository.updateReminder(context, stored.copy(triggerTimeMillis = nextTime))
                    }
                }

                val spokenText = "$category reminder: $title. Again, $category reminder: $title."
                val serviceIntent = Intent(context, TtsAlertService::class.java).apply {
                    putExtra(EXTRA_TITLE, "$category: $title")
                    putExtra(EXTRA_CATEGORY, category)
                    putExtra(EXTRA_SPOKEN_TEXT, spokenText)
                    if (!recurring && id != -1) {
                        putExtra(EXTRA_DELETE_REMINDER_ID, id)
                    }
                }
                startAlertServiceSafely(context, serviceIntent)
            }

            ACTION_TRIGGER_TIMER -> {
                val id = intent.getIntExtra(EXTRA_ID, -1)
                val stored = if (id != -1) {
                    TimerRepository.getTimers(context).find { it.id == id }
                } else null
                val label = (stored?.label ?: intent.getStringExtra(EXTRA_LABEL))?.trim().orEmpty()

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
                startAlertServiceSafely(context, serviceIntent)
            }
        }
    }
}
