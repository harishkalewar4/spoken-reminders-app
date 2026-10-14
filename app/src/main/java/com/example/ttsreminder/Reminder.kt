package com.example.ttsreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Reminder(
    val id: Int,
    val title: String,
    val category: String,
    val triggerTimeMillis: Long,
    val recurring: Boolean = false
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("hh:mm a, MMM dd", Locale.getDefault())
        return sdf.format(Date(triggerTimeMillis))
    }
}

object ReminderRepository {
    const val PREFS_NAME = "tts_reminders_prefs"
    private const val KEY_REMINDERS = "reminders_json"
    private const val KEY_SETUP_DONE = "setup_completed_v1"

    fun isSetupCompleted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SETUP_DONE, false)
    }

    fun setSetupCompleted(context: Context, completed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SETUP_DONE, completed)
            .apply()
    }

    fun getReminders(context: Context): List<Reminder> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_REMINDERS, "[]") ?: "[]"
        val jsonArray = JSONArray(jsonString)
        val list = mutableListOf<Reminder>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(
                Reminder(
                    id = obj.getInt("id"),
                    title = obj.getString("title"),
                    category = obj.optString("category", "General"),
                    triggerTimeMillis = obj.getLong("triggerTimeMillis"),
                    recurring = obj.optBoolean("recurring", false)
                )
            )
        }
        return list.sortedBy { it.triggerTimeMillis }
    }

    fun addReminder(context: Context, reminder: Reminder) {
        val current = getReminders(context).toMutableList()
        current.removeAll { it.id == reminder.id }
        current.add(reminder)
        saveAll(context, current)
        ReminderScheduler.schedule(context, reminder)
    }

    fun updateReminder(context: Context, reminder: Reminder) {
        val current = getReminders(context).toMutableList()
        val index = current.indexOfFirst { it.id == reminder.id }
        if (index != -1) {
            ReminderScheduler.cancel(context, current[index])
            current[index] = reminder
            saveAll(context, current)
            ReminderScheduler.schedule(context, reminder)
        }
    }

    fun deleteReminder(context: Context, reminderId: Int) {
        val current = getReminders(context).toMutableList()
        val toRemove = current.find { it.id == reminderId }
        if (toRemove != null) {
            ReminderScheduler.cancel(context, toRemove)
            current.remove(toRemove)
            saveAll(context, current)
        }
    }

    internal fun saveAll(context: Context, list: List<Reminder>) {
        val jsonArray = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("category", item.category)
                put("triggerTimeMillis", item.triggerTimeMillis)
                put("recurring", item.recurring)
            }
            jsonArray.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REMINDERS, jsonArray.toString())
            .commit()
    }
}

object ReminderScheduler {
    fun schedule(context: Context, reminder: Reminder) {
        var triggerAt = reminder.triggerTimeMillis
        val now = System.currentTimeMillis()
        if (triggerAt <= now) {
            if (reminder.recurring) {
                while (triggerAt <= now) {
                    triggerAt = computeNextOccurrenceAfter(triggerAt)
                }
            } else {
                return
            }
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TRIGGER_REMINDER
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(ReminderReceiver.EXTRA_ID, reminder.id)
            putExtra(ReminderReceiver.EXTRA_TITLE, reminder.title)
            putExtra(ReminderReceiver.EXTRA_CATEGORY, reminder.category)
            putExtra(ReminderReceiver.EXTRA_RECURRING, reminder.recurring)
        }

        val operationPi = PendingIntent.getBroadcast(
            context,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showActivityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val showActivityPi = PendingIntent.getActivity(
            context,
            reminder.id,
            showActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }

            if (canExact) {
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAt, showActivityPi)
                alarmManager.setAlarmClock(alarmClockInfo, operationPi)
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    operationPi
                )
            }
        } catch (e: SecurityException) {
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    operationPi
                )
            } catch (ignored: Exception) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, operationPi)
            }
        }
    }

    fun cancel(context: Context, reminder: Reminder) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TRIGGER_REMINDER
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun calculateNextTriggerMillis(hourOfDay: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(now) || target == now) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    fun computeNextOccurrenceAfter(timeMillis: Long): Long {
        val cal = Calendar.getInstance().apply {
            this.timeInMillis = timeMillis
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
