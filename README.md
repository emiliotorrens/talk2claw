# Talk2Claw 🐾

Voice assistant app for Android — talk directly to your OpenClaw agent using natural speech.

## Features

- 🎤 **Voice-first**: press-to-talk or continuous conversation mode
- 🔊 **High-quality TTS**: Google Cloud Neural2/Studio/Wavenet voices with streaming playback
- ⚡ **Low latency**: sentence chunking + parallel synthesis pipeline (~300ms to first audio)
- 🗣️ **Voice interruption**: speak while Claw is talking to interrupt (auto/always/never modes)
- 🔌 **WebSocket**: native OpenClaw gateway protocol with auto-reconnect
- 🔐 **Device identity**: Ed25519 keypair + challenge-response authentication
- 🎨 **Animated UI**: state indicators (listening waves, thinking dots, speaking equalizer)
- 🗂️ **Persistent history**: conversation transcript saved locally (Room DB)
- 📱 **Widget & Quick Tile**: 1x1 home screen widget + Quick Settings tile for instant access
- 👋 **Wake word** (beta): "Oye Claw" hands-free activation via Picovoice Porcupine
- 🔒 **Voice Match** (framework): on-device speaker verification — only responds to your voice
- 🎧 **Smart echo cancellation**: earpiece routing, volume reduction, fuzzy echo detection

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
- **Speak while Claw is talking** to interrupt (configurable: auto/always/never)
- Response appears as text bubble and is spoken aloud
- **Widget**: add the 1x1 Talk2Claw widget to your home screen for one-tap conversation
- **Quick Settings tile**: pull down notification shade → Talk2Claw tile

### Interruption Modes

| Mode | Behavior |
|---|---|
| **Auto** (default) | Interruption with headphones, wait for TTS with speaker |
| **Always** | Always allow voice interruption |
| **Never** | Wait for Claw to finish before listening |

### Wake Word (Beta)

1. Get a free access key at [console.picovoice.ai](https://console.picovoice.ai/)
2. Settings → Wake Word → enable + paste access key
3. Say "Porcupine" to activate (custom "Oye Claw" keyword coming soon)
4. Works in background via foreground service
5. ⚠️ Disable battery optimization for Talk2Claw in Android settings

### Voice Match (Framework)

Speaker verification framework is implemented but requires an ONNX embedding model (~20MB) to be bundled. When available:
1. Settings → Voice Match → enable
2. Tap "Registrar voz" and speak 3 phrases
3. Adjust similarity threshold (default 0.7)
4. Claw will only respond to your enrolled voice

## Voice Options

| Voice | Type | Quality |
|---|---|---|
| es-ES-Chirp3-HD-* (8 voces) | Chirp3 HD | ⭐⭐⭐⭐⭐ |
| es-ES-Chirp-HD-* (3 voces) | Chirp HD | ⭐⭐⭐⭐⭐ |
| es-ES-Studio-F / C | Studio | ⭐⭐⭐⭐ |
| es-ES-Neural2-B / A | Neural2 | ⭐⭐⭐⭐ |
| es-ES-Wavenet-B / A | Wavenet | ⭐⭐⭐ |
| es-ES-Standard-B / A | Standard | ⭐⭐ |

Speed adjustable from 0.8x to 1.3x in Settings.

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Networking**: OkHttp (WebSocket + HTTP)
- **Crypto**: BouncyCastle (Ed25519 device identity)
- **Audio**: Android AudioTrack (MODE_STREAM), SpeechRecognizer
- **TTS**: Google Cloud Text-to-Speech REST API
- **Persistence**: Room DB (transcript history)
- **Wake Word**: Picovoice Porcupine SDK
- **Speaker Verification**: ONNX Runtime (framework ready)

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

## Roadmap

- **Phase 8**: gRPC Streaming TTS — migrate from REST to streaming synthesis (~100ms first audio vs ~300ms)
- **Phase 9**: Gemini Live / audio-native exploration — evaluate direct audio generation
- Custom "Oye Claw" wake word (Picovoice Console)
- Bundle ECAPA-TDNN model for real speaker verification

## Inspiration

Built from scratch, inspired by [VisionClaw](https://github.com/Intent-Lab/VisionClaw) — a real-time AI assistant for Meta Ray-Ban smart glasses using Gemini Live + OpenClaw.

## License

MIT
