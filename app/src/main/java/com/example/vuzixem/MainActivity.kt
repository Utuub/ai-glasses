package com.example.vuzixem

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Main screen: a message box, Ask Claude, Send to Slack. Everything works by voice (VoiceService)
 * or by buttons/keys. Voice commands: "record message", "delete message", "send to slack",
 * "send to claude", "ask question", "go home", "open app", "cancel".
 */
class MainActivity : AppCompatActivity() {

    private lateinit var questionInput: EditText
    private lateinit var statusView: TextView
    private lateinit var voiceView: TextView
    private lateinit var answerView: TextView
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        questionInput = findViewById(R.id.questionInput)
        statusView = findViewById(R.id.statusView)
        voiceView = findViewById(R.id.voiceView)
        answerView = findViewById(R.id.answerView)

        findViewById<Button>(R.id.askButton).setOnClickListener { askClaude(questionInput.text.toString()) }
        findViewById<Button>(R.id.slackButton).setOnClickListener { sendToSlack(questionInput.text.toString()) }
        questionInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                askClaude(questionInput.text.toString()); true
            } else false
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
            else Log.w(TAG, "No TTS engine available")
        }
        // Mute the mic while the app talks, or it hears itself.
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { runOnUiThread { VoiceBus.service?.setPaused(true) } }
            override fun onDone(id: String?) { runOnUiThread { VoiceBus.service?.setPaused(false) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { runOnUiThread { VoiceBus.service?.setPaused(false) } }
            override fun onStop(id: String?, interrupted: Boolean) { runOnUiThread { VoiceBus.service?.setPaused(false) } }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { VoiceBus.status.collect { voiceView.text = "🎤 $it" } }
                launch { VoiceBus.eventFlow.collect { onVoice(it) } }
            }
        }

        startVoiceWhenAllowed()
    }

    override fun onResume() { super.onResume(); VoiceBus.appVisible = true }
    override fun onPause() { VoiceBus.appVisible = false; super.onPause() }

    private fun onVoice(e: VoiceEvent) {
        when (e) {
            is VoiceEvent.Dictated -> {
                questionInput.setText(e.text)
                questionInput.setSelection(e.text.length)
                if (e.thenAsk) askClaude(e.text)
                else { status("Message ready: say \"send to slack\" or \"send to claude\""); speak("Got it") }
            }
            is VoiceEvent.Command -> when (e.cmd) {
                Cmd.DELETE -> { questionInput.text.clear(); answerView.text = ""; status("Message deleted"); speak("Deleted") }
                Cmd.SEND_SLACK -> sendToSlack(questionInput.text.toString())
                Cmd.SEND_CLAUDE -> askClaude(questionInput.text.toString())
                Cmd.CANCEL -> { tts?.stop(); VoiceBus.service?.setPaused(false); status("Ready") }
                else -> {}
            }
        }
    }

    // ---- Voice service startup ----

    private fun startVoiceWhenAllowed() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startVoice()
        else ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice()
        else VoiceBus.status.value = "No mic permission: voice off"
    }

    private fun startVoice() {
        ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java))
        // Needed so "open app" can bring the app back from the home screen.
        if (!Settings.canDrawOverlays(this)) status("Allow \"Display over other apps\" so \"open app\" works")
    }

    // ---- Actions ----

    fun askClaude(question: String) {
        val q = question.trim()
        if (q.isEmpty()) { status("No message yet: say \"record message\""); speak("No message yet"); return }
        status("Asking Claude...")
        answerView.text = ""
        lifecycleScope.launch {
            val start = SystemClock.elapsedRealtime()
            val answer = ClaudeClient.ask(q)
            val ms = SystemClock.elapsedRealtime() - start
            Log.i(TAG, "Claude answered in $ms ms")
            answerView.text = answer
            if (answer.startsWith("Error")) {
                status("Claude failed")
            } else {
                status("Answered in %.1f s".format(ms / 1000.0))
                speak(answer)
            }
        }
    }

    /** Posts [message] to Slack as a plain message, exactly as written (no Q&A, no header). */
    fun sendToSlack(message: String) {
        val text = message.trim()
        if (text.isEmpty()) { status("No message yet: say \"record message\""); speak("No message yet"); return }
        status("Sending to Slack...")
        lifecycleScope.launch {
            val start = SystemClock.elapsedRealtime()
            val ok = SlackClient.send(text)
            Log.i(TAG, "Slack ok=$ok in ${SystemClock.elapsedRealtime() - start} ms")
            if (ok) {
                status("✓ Sent to Slack")
                speak("Sent to Slack")
                questionInput.text.clear()
            } else {
                status("Slack failed")
                speak("Slack failed")
            }
        }
    }

    // Button-only shortcut (the LX1 profile has no touchscreen): Menu key = send the box's text to Slack.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MENU -> { sendToSlack(questionInput.text.toString()); true }
        else -> super.onKeyDown(keyCode, event)
    }

    private fun status(msg: String) { statusView.text = msg }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt")
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AIGlasses"
        private const val REQ_PERMS = 1
    }
}
