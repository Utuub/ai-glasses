# AI Glasses

A hands-free voice assistant for smart glasses, built for electricians working in the field.

When you're up on a ladder or have your hands full of wire, stopping to look something up on a phone is a hassle. This app lets an electrician ask a question out loud and hear the answer through the glasses, without touching anything.

## What it does

- **Answers code questions.** Ask about the National Electrical Code and get a short, spoken answer that cites the NEC section it comes from. If the assistant isn't sure, it says so instead of guessing.
- **Fast enough to use on the job.** Answers come back in under two seconds.
- **Messages the crew.** Send a quick note to the team's Slack channel straight from the glasses.
- **Fully hands-free.** Offline voice commands (Vosk) run the whole app, even from outside it. The glasses' buttons still work as a backup.

## How it works

The app runs on Android-based smart glasses (Vuzix LX1). A question goes to Claude, a large language model, with instructions to keep answers to two or three sentences and always cite the code section. The answer shows on the glasses' display and is read aloud with Android's built-in text-to-speech.

## Voice commands

| Say | What happens |
| --- | --- |
| "record message" / "new message" | Records what you say next into the message box |
| "delete message" / "clear message" | Clears the message box |
| "send to slack" | Posts the message to the crew's Slack channel |
| "send to claude" / "ask claude" | Asks the assistant the message, answer shown and read aloud |
| "ask question" | Records a question and asks it right away |
| "go home" / "leave app" / "close app" | Goes to the home screen (keeps listening) |
| "open app" / "open glasses" | Brings the app back |
| "cancel" | Stops the answer being read aloud |

**Push-to-talk:** press the `` ` `` key, talk, press it again (auto-stops after 30 s). A command phrase runs that command; anything else goes into the message box. Each press opens a fresh mic, so it works even when always-on listening is having trouble.

Listening runs in a background service, so commands work after leaving the app. Bringing the app back from the home screen needs the "Display over other apps" permission (`adb shell appops set com.example.vuzixem SYSTEM_ALERT_WINDOW allow`). The speech model (~40 MB) downloads into `app/src/main/assets/model-en-us/` on the first build.

Debug builds can fake speech for testing without a mic:
`adb shell am broadcast -a com.example.vuzixem.SAY --es text "send to slack"`

## Built with

Kotlin, Android, Vosk, OkHttp, Kotlin coroutines, Android TextToSpeech, Claude, Slack.

## What's next

- Answers grounded in job-specific documents, not just the NEC
