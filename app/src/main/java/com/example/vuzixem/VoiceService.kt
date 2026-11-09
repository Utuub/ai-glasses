package com.example.vuzixem

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/** What the user asked for by voice. */
enum class Cmd { RECORD, DELETE, SEND_SLACK, SEND_CLAUDE, ASK_QUESTION, GO_HOME, OPEN_APP, CANCEL }

sealed class VoiceEvent {
    data class Command(val cmd: Cmd) : VoiceEvent()
    /** Free speech captured after "record message" ([thenAsk] = false) or "ask question" (true). */
    data class Dictated(val text: String, val thenAsk: Boolean) : VoiceEvent()
}

/** Links the always-on voice service to the screen. Same process, so plain objects are enough. */
object VoiceBus {
    /** Buffered, so a command heard while the app is in the background waits until the screen is back. */
    val events = Channel<VoiceEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()
    val status = MutableStateFlow("Voice starting...")
    @Volatile var appVisible = false
    @Volatile var service: VoiceService? = null
}

/**
 * Offline voice control (Vosk). Runs as a microphone foreground service so it keeps listening
 * after "go home" and can bring the app back with "open app".
 *
 * Two modes: COMMAND listens only for the phrases in [PHRASES] (accurate in noise),
 * DICTATE listens to free speech until the user stops talking.
 */
class VoiceService : Service(), RecognitionListener {

    private enum class Mode { COMMAND, DICTATE }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var mode = Mode.COMMAND
    private var paused = false

    // Dictation state
    private var thenAsk = false
    private val dictated = StringBuilder()
    private var lastPartial = ""
    private var lastSpeechAt = 0L
    private var dictateStartedAt = 0L

    override fun onCreate() {
        super.onCreate()
        VoiceBus.service = this
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        StorageService.unpack(this, "model-en-us", "model",
            { m -> model = m; listenForCommands() },
            { e -> Log.e(TAG, "Model failed to load", e); VoiceBus.status.value = "Voice failed to load" })
        if (BuildConfig.DEBUG) {
            registerReceiver(fakeSpeech, IntentFilter(ACTION_SAY), Context.RECEIVER_EXPORTED)
        }
    }

    /**
     * Debug builds only: pretend the user said something, for testing without a mic.
     * adb shell am broadcast -a com.example.vuzixem.SAY --es text "send to slack"
     */
    private val fakeSpeech = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val text = i.getStringExtra("text")?.trim()?.lowercase() ?: return
            if (mode == Mode.DICTATE) {
                dictated.append(text); lastSpeechAt = SystemClock.elapsedRealtime() - SILENCE_MS
            } else onResult(JSONObject().put("text", PHRASES.keys.firstOrNull { it == text } ?: "[unk]").toString())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (BuildConfig.DEBUG) unregisterReceiver(fakeSpeech)
        stopSpeech()
        model?.close()
        VoiceBus.service = null
        super.onDestroy()
    }

    // ---- Called by the screen ----

    /** Pause while the app is talking so it doesn't hear itself. */
    fun setPaused(p: Boolean) {
        paused = p
        speech?.setPause(p)
        if (!p && mode == Mode.COMMAND) VoiceBus.status.value = LISTENING
    }

    fun startDictation(askAfter: Boolean) {
        val m = model ?: return
        stopSpeech()
        mode = Mode.DICTATE
        thenAsk = askAfter
        dictated.clear(); lastPartial = ""
        dictateStartedAt = SystemClock.elapsedRealtime(); lastSpeechAt = 0L
        speech = SpeechService(Recognizer(m, RATE), RATE).also { it.startListening(this) }
        VoiceBus.status.value = if (askAfter) "Ask your question..." else "Recording... speak your message"
        main.postDelayed(dictationTick, 250)
    }

    // ---- Recognition ----

    private fun listenForCommands() {
        val m = model ?: return
        stopSpeech()
        mode = Mode.COMMAND
        speech = SpeechService(Recognizer(m, RATE, GRAMMAR), RATE).also {
            it.startListening(this)
            if (paused) it.setPause(true)
        }
        VoiceBus.status.value = LISTENING
    }

    private fun stopSpeech() {
        speech?.stop(); speech?.shutdown(); speech = null
    }

    override fun onResult(hypothesis: String) {
        val text = JSONObject(hypothesis).optString("text").trim()
        if (mode == Mode.DICTATE) {
            if (text.isNotEmpty()) {
                if (dictated.isNotEmpty()) dictated.append(' ')
                dictated.append(text)
                lastSpeechAt = SystemClock.elapsedRealtime()
            }
            lastPartial = "" // now part of [dictated]
            return
        }
        if (text.isEmpty() || text == "[unk]") return
        val cmd = PHRASES[text] ?: return
        Log.i(TAG, "Heard \"$text\" -> $cmd")
        VoiceBus.status.value = "Heard: \"$text\""
        handle(cmd)
    }

    override fun onPartialResult(hypothesis: String) {
        if (mode != Mode.DICTATE) return
        val p = JSONObject(hypothesis).optString("partial").trim()
        if (p.isNotEmpty() && p != lastPartial) {
            lastPartial = p
            lastSpeechAt = SystemClock.elapsedRealtime()
            VoiceBus.status.value = "Hearing: ${(dictated.toString() + " " + p).trim()}"
        }
    }

    override fun onFinalResult(hypothesis: String) {}
    override fun onError(e: Exception) { Log.e(TAG, "Recognizer error", e) }
    override fun onTimeout() {}

    /** Ends dictation once the user has been quiet for [SILENCE_MS], or gives up if they never spoke. */
    private val dictationTick = object : Runnable {
        override fun run() {
            if (mode != Mode.DICTATE) return
            val now = SystemClock.elapsedRealtime()
            val spoke = lastSpeechAt > 0
            when {
                spoke && now - lastSpeechAt > SILENCE_MS -> finishDictation()
                !spoke && now - dictateStartedAt > NO_SPEECH_MS -> {
                    VoiceBus.status.value = "Didn't hear anything"
                    listenForCommands()
                }
                now - dictateStartedAt > MAX_DICTATION_MS -> finishDictation()
                else -> main.postDelayed(this, 250)
            }
        }
    }

    private fun finishDictation() {
        // Words Vosk hasn't finalized yet are still in the last partial.
        val text = (dictated.toString() + " " + lastPartial).trim()
        listenForCommands()
        if (text.isEmpty()) { VoiceBus.status.value = "Didn't hear anything"; return }
        deliver(VoiceEvent.Dictated(text.replaceFirstChar { it.uppercase() }, thenAsk))
    }

    // ---- Actions ----

    private fun handle(cmd: Cmd) {
        when (cmd) {
            Cmd.GO_HOME -> startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Cmd.OPEN_APP -> openApp()
            // Dictation is run here (the service owns the mic), even from outside the app.
            Cmd.RECORD -> { openApp(); startDictation(askAfter = false) }
            Cmd.ASK_QUESTION -> { openApp(); startDictation(askAfter = true) }
            else -> deliver(VoiceEvent.Command(cmd))
        }
    }

    /** Hand an event to the screen, bringing the app back first if the user left it. */
    private fun deliver(e: VoiceEvent) {
        if (!VoiceBus.appVisible) openApp()
        VoiceBus.events.trySend(e)
    }

    private fun openApp() {
        if (VoiceBus.appVisible) return
        // Launching from the background needs "Display over other apps" (SYSTEM_ALERT_WINDOW).
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice commands", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("AI Glasses is listening")
            .setContentText("Say \"open app\" to come back")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AIGlasses"
        private const val ACTION_SAY = "com.example.vuzixem.SAY"
        private const val CHANNEL = "voice"
        private const val NOTIF_ID = 1
        private const val RATE = 16000.0f
        private const val LISTENING = "Listening..."
        private const val SILENCE_MS = 1500L
        private const val NO_SPEECH_MS = 8000L
        private const val MAX_DICTATION_MS = 30000L

        /** Spoken phrase -> command. Every word must be in the Vosk model's vocabulary. */
        val PHRASES = mapOf(
            "record message" to Cmd.RECORD,
            "new message" to Cmd.RECORD,
            "delete message" to Cmd.DELETE,
            "clear message" to Cmd.DELETE,
            "send to slack" to Cmd.SEND_SLACK,
            "send to claude" to Cmd.SEND_CLAUDE,
            "ask claude" to Cmd.SEND_CLAUDE,
            "ask question" to Cmd.ASK_QUESTION,
            "go home" to Cmd.GO_HOME,
            "leave app" to Cmd.GO_HOME,
            "close app" to Cmd.GO_HOME,
            "open app" to Cmd.OPEN_APP,
            "open glasses" to Cmd.OPEN_APP,
            "cancel" to Cmd.CANCEL,
        )

        // "[unk]" lets Vosk reject random speech instead of forcing it onto the nearest command.
        private val GRAMMAR = (PHRASES.keys + "[unk]").joinToString(",", "[", "]") { "\"$it\"" }
    }
}
