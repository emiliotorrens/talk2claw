# Talk2Claw 🐾

Voice assistant app for Android — talk directly to your OpenClaw agent.

## Architecture

```
🎤 Mic → Android SpeechRecognizer (STT, on-device, free)
    ↓
Text → OpenClaw Gateway (/v1/chat/completions) → Claude responds
    ↓
Response text → Google Cloud TTS → PCM audio → 🔊 Speaker
```

## Setup

1. Open the app → Settings (gear icon)
2. Configure:
   - **Gateway Host**: Your OpenClaw gateway URL (e.g. `http://100.126.172.37`)
   - **Gateway Port**: `18789` (default)
   - **Gateway Token**: Your OpenClaw auth token
   - **Google Cloud API Key**: For Text-to-Speech
   - **TTS Voice**: `es-ES-Wavenet-B` (default, male Spanish)

3. Green dot = connected to gateway

## Usage

- **Press and hold** the blue mic button to talk
- **Release** to send your message to Claw
- Claw's response appears in the transcript and is spoken aloud
- **Tap while speaking** to stop playback

## Requirements

- Android 9+ (API 28)
- Internet connection
- OpenClaw gateway accessible (same network or Tailscale)
- Google Cloud API key with Text-to-Speech API enabled

## Building

Open in Android Studio, sync Gradle, run on device.

## License

MIT
