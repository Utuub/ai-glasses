package com.example.vuzixem

import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Main screen: ask Claude a question, or send a message to Slack.
 * Input is typed for now. When Vosk (Part 1) is added, its "ask question" / "send to slack"
 * commands should call askClaude(text) and sendToSlack(dictatedMessage) directly.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var questionInput: EditText
    private lateinit var statusView: TextView
    private lateinit var answerView: TextView
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        questionInput = findViewById(R.id.questionInput)
        statusView = findViewById(R.id.statusView)
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
    }

    fun askClaude(question: String) {
        val q = question.trim()
        if (q.isEmpty()) { status("Type a question first"); return }
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
        if (text.isEmpty()) { status("Type a message first"); return }
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

    companion object { private const val TAG = "AIGlasses" }
}
