# Talk2Claw 🐾

Voice assistant app for Android — talk directly to your OpenClaw agent using natural speech.

## Features

- 🎤 **Voice-first**: press-to-talk or continuous conversation mode
- 🔊 **High-quality TTS**: Google Cloud Neural2/Studio/Wavenet voices with streaming playback
- ⚡ **Low latency**: sentence chunking + parallel synthesis pipeline (~300ms to first audio)
- 🗣️ **Voice interruption**: speak while Claw is talking to interrupt
- 🔌 **WebSocket**: native OpenClaw gateway protocol with auto-reconnect
- 🔐 **Device identity**: Ed25519 keypair + challenge-response authentication
- 🎨 **Animated UI**: state indicators (listening waves, thinking dots, speaking equalizer)

## Architecture

```
🎤 Mic → Android SpeechRecognizer (STT, on-device, free)
    ↓
Text → OpenClaw Gateway (WebSocket, chat.send) → Agent responds
    ↓
Response text → Google Cloud TTS (chunked) → AudioTrack streaming → 🔊 Speaker
```

**Transport**: WebSocket with OpenClaw Gateway Protocol v3
- Challenge-response handshake with Ed25519 device identity
- Streaming chat events for real-time responses
- Automatic fallback to HTTP REST if WebSocket unavailable

## Setup

1. Install the APK on your Android device
2. Open the app → Settings (gear icon)
3. Configure:

| Setting | Value |
|---|---|
| **Gateway Host** | `https://your-gateway.tailnet.ts.net` (or `http://ip:port`) |
| **Gateway Port** | `443` (if using Tailscale Serve) or `18789` (direct) |
| **Gateway Token** | Your OpenClaw gateway auth token |
| **Google Cloud API Key** | API key with Cloud Text-to-Speech enabled |
| **TTS Voice** | `es-ES-Neural2-B` (default) — 8 Spanish voices available |

4. Connect → first time requires device pairing approval:
   ```bash
   openclaw devices list     # find the pending request
   openclaw devices approve <request-id>
   ```
5. Green dot = connected ✅

## Usage

- **Tap** the mic button to start listening
- **Speak** your message — it's sent when you stop talking
- **Long-press** toggles continuous conversation mode (auto-listens after each response)
- **Tap while Claw is speaking** to interrupt
- Response appears as text bubble and is spoken aloud

## Voice Options

| Voice | Type | Quality |
|---|---|---|
| es-ES-Neural2-B (default) | Male | ⭐⭐⭐⭐ |
| es-ES-Neural2-A | Female | ⭐⭐⭐⭐ |
| es-ES-Studio-B | Male | ⭐⭐⭐⭐⭐ |
| es-ES-Studio-A | Female | ⭐⭐⭐⭐⭐ |
| es-ES-Wavenet-B | Male | ⭐⭐⭐ |
| es-ES-Wavenet-A | Female | ⭐⭐⭐ |
| es-ES-Standard-B | Male | ⭐⭐ |
| es-ES-Standard-A | Female | ⭐⭐ |

Speed adjustable from 0.8x to 1.3x in Settings.

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Networking**: OkHttp (WebSocket + HTTP)
- **Crypto**: BouncyCastle (Ed25519 device identity)
- **Audio**: Android AudioTrack (MODE_STREAM), SpeechRecognizer
- **TTS**: Google Cloud Text-to-Speech REST API

## Building

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/java17
./gradlew :app:assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

Requirements: Android SDK (API 35), Java 17, Gradle 8.11.1

## Requirements

- Android 9+ (API 28)
- Internet connection
- OpenClaw gateway accessible (same network or Tailscale)
- Google Cloud API key with [Text-to-Speech API](https://console.cloud.google.com/apis/api/texttospeech.googleapis.com) enabled

## Inspiration

Built from scratch, inspired by [VisionClaw](https://github.com/Intent-Lab/VisionClaw) — a real-time AI assistant for Meta Ray-Ban smart glasses using Gemini Live + OpenClaw.

## License

MIT
