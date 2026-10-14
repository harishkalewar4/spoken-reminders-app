package com.example.ttsreminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Initialize the default Android system TextToSpeech engine
        tts = TextToSpeech(applicationContext, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(ReminderReceiver.EXTRA_TITLE) ?: "Reminder"
        val category = intent?.getStringExtra(ReminderReceiver.EXTRA_CATEGORY) ?: "General"

        val spokenText = "$category reminder: $title. Again, $category reminder: $title."

        val notification = buildForegroundNotification("$category: $title")
        startForeground(NOTIFICATION_ID, notification)

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

            // Route speech through the ALARM stream so the user hears it clearly
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
            while (pendingMessages.isNotEmpty()) {
                val msg = pendingMessages.poll()
                if (msg != null) {
                    speakOutLoud(msg)
                }
            }
        } else {
            // If the system TTS fails to initialize, stop service gracefully
            stopSelfSafely()
        }
    }

    private fun speakOutLoud(text: String) {
        requestAudioFocus()
        val utteranceId = "REMINDER_UTTERANCE_${System.currentTimeMillis()}_${activeUtterances.incrementAndGet()}"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }

    private fun onUtteranceFinished() {
        if (activeUtterances.decrementAndGet() <= 0 && pendingMessages.isEmpty()) {
            // Give a brief 500ms buffer before tearing down audio focus and stopping the service
            mainHandler.postDelayed({
                abandonAudioFocus()
                stopSelfSafely()
            }, 500L)
        }
    }

    private fun requestAudioFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        focusRequest = req
        audioManager?.requestAudioFocus(req)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SpokenReminders::TtsWakeLock"
        ).apply {
            acquire(60_000L) // Timeout after 60 seconds max
        }
    }

    private fun stopSelfSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
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
                description = "Speaks reminders aloud using the system Text-to-Speech engine."
                setSound(null, null) // Silence standard chime so it doesn't talk over the TTS voice
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Speaking Reminder")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
    }
}
