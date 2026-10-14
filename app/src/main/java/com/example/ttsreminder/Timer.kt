package com.example.ttsreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

data class TimerItem(
    val id: Int,
    val label: String,
    val durationMillis: Long,
    val endTimeMillis: Long
)

object TimerRepository {
    const val PREFS_NAME = "tts_timers_prefs"
    private const val KEY_TIMERS = "timers_json"

    fun getTimers(context: Context): List<TimerItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_TIMERS, "[]") ?: "[]"
        val jsonArray = JSONArray(jsonString)
        val list = mutableListOf<TimerItem>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(
                TimerItem(
                    id = obj.getInt("id"),
                    label = obj.optString("label", ""),
                    durationMillis = obj.getLong("durationMillis"),
                    endTimeMillis = obj.getLong("endTimeMillis")
                )
            )
        }
        return list.sortedBy { it.endTimeMillis }
    }

    fun addTimer(context: Context, timer: TimerItem) {
        val current = getTimers(context).toMutableList()
        current.removeAll { it.id == timer.id }
        current.add(timer)
        saveAll(context, current)
        TimerScheduler.schedule(context, timer)
    }

    fun deleteTimer(context: Context, timerId: Int) {
        val current = getTimers(context).toMutableList()
        val toRemove = current.find { it.id == timerId }
        if (toRemove != null) {
            TimerScheduler.cancel(context, toRemove)
            current.remove(toRemove)
            saveAll(context, current)
        }
    }

    internal fun saveAll(context: Context, list: List<TimerItem>) {
        val jsonArray = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("label", item.label)
                put("durationMillis", item.durationMillis)
                put("endTimeMillis", item.endTimeMillis)
            }
            jsonArray.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TIMERS, jsonArray.toString())
            .commit()
    }
}

object TimerScheduler {
    fun schedule(context: Context, timer: TimerItem) {
        if (timer.endTimeMillis <= System.currentTimeMillis()) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TRIGGER_TIMER
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(ReminderReceiver.EXTRA_ID, timer.id)
            putExtra(ReminderReceiver.EXTRA_LABEL, timer.label)
        }

        val operationPi = PendingIntent.getBroadcast(
            context,
            timer.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showActivityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val showActivityPi = PendingIntent.getActivity(
            context,
            timer.id,
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
                val alarmClockInfo = AlarmManager.AlarmClockInfo(timer.endTimeMillis, showActivityPi)
                alarmManager.setAlarmClock(alarmClockInfo, operationPi)
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    timer.endTimeMillis,
                    operationPi
                )
            }
        } catch (e: SecurityException) {
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    timer.endTimeMillis,
                    operationPi
                )
            } catch (ignored: Exception) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, timer.endTimeMillis, operationPi)
            }
        }
    }

    fun cancel(context: Context, timer: TimerItem) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TRIGGER_TIMER
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            timer.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
