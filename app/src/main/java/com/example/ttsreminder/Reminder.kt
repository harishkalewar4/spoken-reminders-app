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
    val triggerTimeMillis: Long
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("hh:mm a, MMM dd", Locale.getDefault())
        return sdf.format(Date(triggerTimeMillis))
    }
}

object ReminderRepository {
    private const val PREFS_NAME = "tts_reminders_prefs"
    private const val KEY_REMINDERS = "reminders_json"

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
                    triggerTimeMillis = obj.getLong("triggerTimeMillis")
                )
            )
        }
        return list.sortedBy { it.triggerTimeMillis }
    }

    fun addReminder(context: Context, reminder: Reminder) {
        val current = getReminders(context).toMutableList()
        current.add(reminder)
        saveAll(context, current)
        ReminderScheduler.schedule(context, reminder)
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

    private fun saveAll(context: Context, list: List<Reminder>) {
        val jsonArray = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("category", item.category)
                put("triggerTimeMillis", item.triggerTimeMillis)
            }
            jsonArray.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REMINDERS, jsonArray.toString())
            .apply()
    }
}

object ReminderScheduler {
    fun schedule(context: Context, reminder: Reminder) {
        if (reminder.triggerTimeMillis <= System.currentTimeMillis()) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TRIGGER_REMINDER
            putExtra(ReminderReceiver.EXTRA_ID, reminder.id)
            putExtra(ReminderReceiver.EXTRA_TITLE, reminder.title)
            putExtra(ReminderReceiver.EXTRA_CATEGORY, reminder.category)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.triggerTimeMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.triggerTimeMillis,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, reminder.triggerTimeMillis, pendingIntent)
        }
    }

    fun cancel(context: Context, reminder: Reminder) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TRIGGER_REMINDER
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
        // If the selected time has already passed today, schedule it for tomorrow
        if (target.before(now) || target == now) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }
}
