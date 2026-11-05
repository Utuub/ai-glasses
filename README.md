# AI Glasses

A hands-free voice assistant for smart glasses, built for electricians working in the field.

When you're up on a ladder or have your hands full of wire, stopping to look something up on a phone is a hassle. This app lets an electrician ask a question out loud and hear the answer through the glasses, without touching anything.

## What it does

- **Answers code questions.** Ask about the National Electrical Code and get a short, spoken answer that cites the NEC section it comes from. If the assistant isn't sure, it says so instead of guessing.
- **Fast enough to use on the job.** Answers come back in under two seconds.
- **Messages the crew.** Send a quick note to the team's Slack channel straight from the glasses.
- **Works without a touchscreen.** The glasses only have buttons, so every action maps to a button press, with voice commands coming next.

## How it works

The app runs on Android-based smart glasses (Vuzix LX1). A question goes to Claude, a large language model, with instructions to keep answers to two or three sentences and always cite the code section. The answer shows on the glasses' display and is read aloud with Android's built-in text-to-speech.

## Built with

Kotlin, Android, OkHttp, Kotlin coroutines, Android TextToSpeech, Claude, Slack.

## What's next

- Offline voice commands, so the whole thing runs without typing
- Answers grounded in job-specific documents, not just the NEC
