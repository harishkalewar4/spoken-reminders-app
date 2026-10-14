package com.example.ttsreminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class TtsAlertService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val CHANNEL_ID = "tts_reminder_alert_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private val pendingMessages = ConcurrentLinkedQueue<String>()
    private val activeUtterances = AtomicInteger(0)
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reminderToRemoveId: Int = -1

    override fun onCreate() {
        super.onCreate()

        try {
            createNotificationChannel()
        } catch (t: Throwable) {
            ReminderRepository.recordAlarmEvent(
                applicationContext,
                "onCreate channel FAILED: ${t.javaClass.simpleName}: ${t.message}"
            )
        }

        try {
            acquireWakeLock()
        } catch (t: Throwable) {
            ReminderRepository.recordAlarmEvent(
                applicationContext,
                "onCreate wakeLock FAILED: ${t.javaClass.simpleName}: ${t.message}"
            )
        }

        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        } catch (t: Throwable) {
            ReminderRepository.recordAlarmEvent(
                applicationContext,
                "onCreate audioManager FAILED: ${t.javaClass.simpleName}: ${t.message}"
            )
        }

        val initialNotification = try {
            buildForegroundNotification("Spoken Reminder Alert")
        } catch (t: Throwable) {
            ReminderRepository.recordAlarmEvent(
                applicationContext,
                "onCreate buildNotif FAILED: ${t.javaClass.simpleName}: ${t.message}"
            )
            null
        }

        if (initialNotification != null) {
            ReminderRepository.recordAlarmEvent(applicationContext, "calling startForeground")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        initialNotification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, initialNotification)
                }
                ReminderRepository.recordAlarmEvent(applicationContext, "service started")
            } catch (t: Throwable) {
                val errNote = "startForeground FAILED: ${t.javaClass.simpleName}: ${t.message}"
                ReminderRepository.recordAlarmEvent(applicationContext, errNote)
                ReminderReceiver.postFallbackFullScreenNotification(
                    context = applicationContext,
                    title = "Speaking Reminder",
                    contentText = "Spoken Reminder Alert",
                    reasonNote = errNote
                )
            }
        }

        try {
            tts = TextToSpeech(applicationContext, this)
        } catch (t: Throwable) {
            ReminderRepository.recordAlarmEvent(
                applicationContext,
                "onCreate TTS ctor FAILED: ${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(ReminderReceiver.EXTRA_TITLE) ?: "Reminder"
        val category = intent?.getStringExtra(ReminderReceiver.EXTRA_CATEGORY) ?: "General"
        val customSpoken = intent?.getStringExtra(ReminderReceiver.EXTRA_SPOKEN_TEXT)
        val spokenText = customSpoken ?: "$category reminder: $title. Again, $category reminder: $title."

        val extraId = intent?.getIntExtra(ReminderReceiver.EXTRA_ID, -1) ?: -1
        if (extraId != -1) {
            reminderToRemoveId = extraId
        }

        try {
            val notification = buildForegroundNotification("$category: $title")
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            ReminderRepository.recordAlarmEvent(
                applicationContext,
                "onStartCommand notify FAILED: ${t.javaClass.simpleName}: ${t.message}"
            )
        }

        if (isTtsInitialized) {
            speakOutLoud(spokenText)
        } else {
            pendingMessages.add(spokenText)
        }

        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val langResult = engine.setLanguage(Locale.getDefault())
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.US)
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            engine.setAudioAttributes(audioAttributes)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    onUtteranceFinished()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onUtteranceFinished()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onUtteranceFinished()
                }
            })

            isTtsInitialized = true
            ReminderRepository.recordAlarmEvent(applicationContext, "service started -> TTS initialized, speaking")
            while (pendingMessages.isNotEmpty()) {
                val msg = pendingMessages.poll()
                if (msg != null) {
                    speakOutLoud(msg)
                }
            }
        } else {
            ReminderRepository.recordAlarmEvent(applicationContext, "TTS init failed (status=$status)")
            stopSelfSafely()
        }
    }

    private fun speakOutLoud(text: String) {
        requestAudioFocus()
        val utteranceId = "REMINDER_UTTERANCE_${System.currentTimeMillis()}_${activeUtterances.incrementAndGet()}"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
        }
        val speakResult = tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        if (speakResult == TextToSpeech.ERROR) {
            ReminderRepository.recordAlarmEvent(applicationContext, "TTS speak() returned ERROR")
            onUtteranceFinished()
        }
    }

    private fun onUtteranceFinished() {
        if (activeUtterances.decrementAndGet() <= 0 && pendingMessages.isEmpty()) {
            ReminderRepository.recordAlarmEvent(applicationContext, "speech finished")
            if (reminderToRemoveId != -1) {
                ReminderRepository.removeReminderRecordOnly(applicationContext, reminderToRemoveId)
                reminderToRemoveId = -1
            }
            mainHandler.postDelayed({
                abandonAudioFocus()
                stopSelfSafely()
            }, 500L)
        }
    }

    private fun requestAudioFocus() {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            focusRequest = req
            audioManager?.requestAudioFocus(req)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun abandonAudioFocus() {
        try {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SpokenReminders::TtsWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(60_000L)
        }
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (reminderToRemoveId != -1) {
            ReminderRepository.removeReminderRecordOnly(applicationContext, reminderToRemoveId)
            reminderToRemoveId = -1
        }
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Spoken Reminder Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Speaks reminders and timers aloud using the system Text-to-Speech engine."
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPi = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Speaking Reminder")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setAutoCancel(true)
            .build()
    }
}
