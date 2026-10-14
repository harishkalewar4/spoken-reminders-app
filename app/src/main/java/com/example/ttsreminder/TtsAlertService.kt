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

    private data class QueuedSpeech(
        val spokenText: String,
        val deleteReminderIdAfter: Int
    )

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private val pendingQueue = ConcurrentLinkedQueue<QueuedSpeech>()
    private val reminderIdsPendingDeletion = ConcurrentLinkedQueue<Int>()
    private val activeUtterances = AtomicInteger(0)
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val safetyStopRunnable = Runnable {
        flushPendingDeletions()
        abandonAudioFocus()
        stopSelfSafely()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireOrExtendWakeLock()
        ReminderReceiver.releaseTransitionWakeLock()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val initialNotification = buildForegroundNotification("Spoken Reminder Alert")
        startForegroundCompat(initialNotification)

        tts = TextToSpeech(applicationContext, this)
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (ignored: Exception) {
                ignored.printStackTrace()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireOrExtendWakeLock()
        ReminderReceiver.releaseTransitionWakeLock()

        val title = intent?.getStringExtra(ReminderReceiver.EXTRA_TITLE) ?: "Reminder"
        val customSpoken = intent?.getStringExtra(ReminderReceiver.EXTRA_SPOKEN_TEXT)
        val spokenText = customSpoken ?: title
        val deleteReminderId = intent?.getIntExtra(ReminderReceiver.EXTRA_DELETE_REMINDER_ID, -1) ?: -1

        val notification = buildForegroundNotification(title)
        startForegroundCompat(notification)

        mainHandler.removeCallbacks(safetyStopRunnable)
        mainHandler.postDelayed(safetyStopRunnable, 60_000L)

        val item = QueuedSpeech(spokenText = spokenText, deleteReminderIdAfter = deleteReminderId)
        if (isTtsInitialized) {
            speakItem(item)
        } else {
            pendingQueue.add(item)
        }

        return START_REDELIVER_INTENT
    }

    override fun onInit(status: Int) {
        mainHandler.post {
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine == null) {
                    stopSelfSafely()
                    return@post
                }

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
                        mainHandler.post { onUtteranceFinished() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { onUtteranceFinished() }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        mainHandler.post { onUtteranceFinished() }
                    }
                })

                isTtsInitialized = true
                while (pendingQueue.isNotEmpty()) {
                    val item = pendingQueue.poll()
                    if (item != null) {
                        speakItem(item)
                    }
                }
            } else {
                flushPendingDeletions()
                stopSelfSafely()
            }
        }
    }

    private fun speakItem(item: QueuedSpeech) {
        acquireOrExtendWakeLock()
        requestAudioFocus()
        if (item.deleteReminderIdAfter != -1) {
            reminderIdsPendingDeletion.add(item.deleteReminderIdAfter)
        }

        val utteranceId = "REMINDER_UTTERANCE_${System.currentTimeMillis()}_${activeUtterances.incrementAndGet()}"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
        }
        val result = tts?.speak(item.spokenText, TextToSpeech.QUEUE_ADD, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            onUtteranceFinished()
        }
    }

    private fun onUtteranceFinished() {
        if (activeUtterances.decrementAndGet() <= 0 && pendingQueue.isEmpty()) {
            activeUtterances.set(0)
            flushPendingDeletions()
            mainHandler.removeCallbacks(safetyStopRunnable)
            mainHandler.postDelayed({
                if (activeUtterances.get() <= 0 && pendingQueue.isEmpty()) {
                    abandonAudioFocus()
                    stopSelfSafely()
                }
            }, 600L)
        }
    }

    private fun flushPendingDeletions() {
        while (reminderIdsPendingDeletion.isNotEmpty()) {
            val id = reminderIdsPendingDeletion.poll()
            if (id != null && id != -1) {
                ReminderRepository.deleteReminder(applicationContext, id)
            }
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun abandonAudioFocus() {
        try {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun acquireOrExtendWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = wakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SpokenReminders::TtsWakeLock"
            ).apply {
                setReferenceCounted(false)
                wakeLock = this
            }
            wl.acquire(90_000L)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        flushPendingDeletions()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        ReminderReceiver.releaseTransitionWakeLock()
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
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Speaking Reminder")
            .setContentText(contentText)
            .setContentIntent(contentPi)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
